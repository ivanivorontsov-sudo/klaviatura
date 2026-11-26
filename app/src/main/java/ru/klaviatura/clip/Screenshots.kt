// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.clip

import android.Manifest
import android.app.Activity
import android.content.ClipDescription
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import helium314.keyboard.latin.utils.prefs

/**
 * A system screenshot is not put into the clipboard unless "Copy" is tapped in the screenshot UI.
 * With this option the newest screenshot (taken in the last 10 minutes) is copied into the clipboard history
 * when the keyboard opens, so it shows as a thumbnail there and can be pasted (commitContent).
 */
object Screenshots {
    const val PREF = "kl_clip_screenshots"
    const val DEFAULT = true // 1.3.1: on by default
    private const val IMPORTED_IDS = "kl_clip_imported_screenshot_ids"
    private const val ASKED = "kl_clip_screenshots_asked"
    private const val WINDOW_S = 10 * 60

    val permission get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun granted(ctx: Context) = ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun enabled(ctx: Context) = ctx.prefs().getBoolean(PREF, DEFAULT)

    /**
     * Screenshot folders: DCIM/Screenshots (ColorOS/realme, OnePlus), Pictures/Screenshots (AOSP, Pixel, Samsung),
     * MIUI "DCIM/Screenshots", plus file names like Screenshot_2026-11-05-09-13-00-12_com.app.jpg / Скриншот_...
     */
    fun isScreenshot(path: String?, name: String?): Boolean {
        val p = (path ?: "").replace('\\', '/').lowercase()
        val n = (name ?: "").lowercase()
        return p.contains("screenshot") || p.contains("скриншот") || p.contains("screen_shot") || p.contains("screencapture") ||
            n.startsWith("screenshot") || n.startsWith("скриншот") || n.startsWith("screen_") || n.startsWith("scr_")
    }

    private const val OFFERED_AT = "kl_clip_perm_offered_at"
    /** strip hint «allow screenshots»: when enabled but not granted, at most once in 3 days */
    fun shouldOfferPermission(ctx: Context): Boolean = enabled(ctx) && !granted(ctx) &&
        System.currentTimeMillis() - ctx.prefs().getLong(OFFERED_AT, 0) > 3 * 24 * 3600_000L
    fun markOffered(ctx: Context) = ctx.prefs().edit().putLong(OFFERED_AT, System.currentTimeMillis()).apply()

    /** settings opened: ask once for the gallery permission (an IME cannot show the dialog itself) */
    fun askOnceFromSettings(activity: Activity) {
        val prefs = activity.prefs()
        if (!enabled(activity) || granted(activity) || prefs.getBoolean(ASKED, false)) return
        prefs.edit().putBoolean(ASKED, true).apply()
        activity.startActivity(android.content.Intent(activity, MediaPermissionActivity::class.java))
    }

    private data class PendingScreenshot(val id: Long, val uri: Uri, val timestamp: Long, val description: ClipDescription)

    /** Select a recent capture time, falling back to MediaStore insertion time when EXIF is stale. */
    internal fun effectiveTimestamp(nowMillis: Long, dateAddedSeconds: Long, dateTakenMillis: Long, id: Long): Long {
        val earliest = nowMillis - WINDOW_S * 1000L
        val latest = nowMillis + 60_000L
        return if (dateTakenMillis in earliest..latest) {
            dateTakenMillis
        } else {
            dateAddedSeconds * 1000L + (id % 1000L)
        }
    }

    /**
     * Import every unseen screenshot in the recent window. The callback runs on the caller's
     * worker thread, keeping image copying and hashing off the keyboard UI thread.
     * An ID is remembered only after the caller confirms that the image was saved.
     */
    fun importRecent(ctx: Context, add: (Uri, Long, ClipDescription) -> Boolean) {
        val prefs = ctx.prefs()
        if (!enabled(ctx) || !granted(ctx)) return
        try {
            val now = System.currentTimeMillis() / 1000
            val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
            val importedIds = prefs.getStringSet(IMPORTED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
            val pending = mutableListOf<PendingScreenshot>()
            ctx.contentResolver.query(col, arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.MIME_TYPE,
                    pathCol,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_TAKEN
                ),
                "${MediaStore.Images.Media.DATE_ADDED} > ?", arrayOf((now - WINDOW_S).toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    if (!isScreenshot(c.getString(3), c.getString(4))) continue
                    val id = c.getLong(0)
                    if (id.toString() in importedIds) continue
                    // DATE_TAKEN may be missing or stale on vendor ROMs. Trust it only
                    // when it falls inside the current import window; otherwise use MediaStore's
                    // insertion time so a fresh capture is not mistaken for an old image.
                    val timestamp = effectiveTimestamp(System.currentTimeMillis(), c.getLong(1), c.getLong(5), id)
                    val uri = ContentUris.withAppendedId(col, id)
                    val mime = c.getString(2) ?: "image/png"
                    pending.add(PendingScreenshot(id, uri, timestamp,
                        ClipDescription("Скриншот", arrayOf(mime))))
                }
            }

            var changed = false
            pending.asReversed().forEach { item ->
                val saved = runCatching { add(item.uri, item.timestamp, item.description) }.getOrDefault(false)
                if (saved) {
                    importedIds.add(item.id.toString())
                    changed = true
                }
            }
            if (changed) {
                val boundedIds = if (importedIds.size > 300) importedIds.toList().takeLast(300).toSet() else importedIds.toSet()
                prefs.edit().putStringSet(IMPORTED_IDS, boundedIds).apply()
            }
        } catch (e: Throwable) { android.util.Log.w("KlScreenshots", "import failed", e) }
    }

    /** New screenshots arrive while the keyboard is alive; some ROMs publish rows in stages. */
    fun observer(ctx: Context, handler: android.os.Handler, add: (Uri, Long, ClipDescription) -> Boolean) =
        object : android.database.ContentObserver(handler) {
            private fun scheduleImport() {
                // The MediaStore row or image bytes can appear after the first notification.
                // Debounce vendor-ROM bursts, then retry long enough for the file to finish writing.
                handler.removeCallbacksAndMessages(this)
                val now = android.os.SystemClock.uptimeMillis()
                for (delay in longArrayOf(350, 1200, 3000, 6500)) {
                    handler.postAtTime({ importRecent(ctx, add) }, this, now + delay)
                }
            }

            override fun onChange(selfChange: Boolean) = scheduleImport()
            override fun onChange(selfChange: Boolean, uri: Uri?) = scheduleImport()
            override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) = scheduleImport()
        }.also { observer ->
            try {
                ctx.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            } catch (e: Throwable) {
                android.util.Log.w("KlScreenshots", "cannot observe MediaStore changes", e)
            }
        }
}

/** asks for the gallery permission needed for screenshots in the clipboard */
class MediaPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Screenshots.granted(this)) { finish(); return }
        requestPermissions(arrayOf(Screenshots.permission), 1)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Toast.makeText(this, if (ok) "Свежие скриншоты будут появляться в буфере клавиатуры" else "Без доступа к фото скриншоты не попадут в буфер", Toast.LENGTH_LONG).show()
        finish()
    }
}

/** pure decision whether a clip item is a file (image) rather than text — unit-tested */
object ClipKind {
    fun isFile(scheme: String?, uriType: String?, descHasText: Boolean, textEmpty: Boolean): Boolean {
        if (scheme == null) return false
        if (uriType != null && !uriType.startsWith("text/")) return true
        if (!descHasText) return true
        return textEmpty && scheme == "content"
    }
}
