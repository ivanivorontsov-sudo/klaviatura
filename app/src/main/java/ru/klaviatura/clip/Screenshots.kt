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
    private const val LAST = "kl_clip_last_screenshot"
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

    /** settings opened: ask once for the gallery permission (an IME cannot show the dialog itself) */
    fun askOnceFromSettings(activity: Activity) {
        val prefs = activity.prefs()
        if (!enabled(activity) || granted(activity) || prefs.getBoolean(ASKED, false)) return
        prefs.edit().putBoolean(ASKED, true).apply()
        activity.startActivity(android.content.Intent(activity, MediaPermissionActivity::class.java))
    }

    fun importRecent(ctx: Context, add: (Uri, Long, ClipDescription) -> Unit) {
        val prefs = ctx.prefs()
        if (!enabled(ctx) || !granted(ctx)) return
        try {
            val now = System.currentTimeMillis() / 1000
            val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
            ctx.contentResolver.query(col, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.MIME_TYPE, pathCol, MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media.DATE_ADDED} > ?", arrayOf((now - WINDOW_S).toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                var seen = 0
                while (c.moveToNext() && seen++ < 20) {
                    if (!isScreenshot(c.getString(3), c.getString(4))) continue
                    val id = c.getLong(0)
                    if (prefs.getLong(LAST, -1) == id) return
                    val uri = ContentUris.withAppendedId(col, id)
                    add(uri, c.getLong(1) * 1000, ClipDescription("Скриншот", arrayOf(c.getString(2) ?: "image/png")))
                    prefs.edit().putLong(LAST, id).apply()
                    return
                }
            }
        } catch (e: Throwable) { android.util.Log.w("KlScreenshots", "import failed", e) }
    }

    /** new screenshots arrive while the keyboard is alive (ColorOS writes the file a moment after the flash) */
    fun observer(ctx: Context, handler: android.os.Handler, add: (Uri, Long, ClipDescription) -> Unit) =
        object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                // the file may still be pending: retry shortly
                handler.removeCallbacksAndMessages(this)
                for (d in longArrayOf(300, 1500, 4000)) handler.postAtTime({ importRecent(ctx, add) }, this, android.os.SystemClock.uptimeMillis() + d)
            }
        }.also { o ->
            try { ctx.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o) } catch (e: Throwable) { }
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
        if (!ok) prefs().edit().putBoolean(Screenshots.PREF, false).apply()
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
