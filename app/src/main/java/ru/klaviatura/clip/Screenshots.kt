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
    private const val LAST = "kl_clip_last_screenshot"
    private const val WINDOW_S = 10 * 60

    val permission get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun granted(ctx: Context) = ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun importRecent(ctx: Context, add: (Uri, Long, ClipDescription) -> Unit) {
        val prefs = ctx.prefs()
        if (!prefs.getBoolean(PREF, false) || !granted(ctx)) return
        try {
            val now = System.currentTimeMillis() / 1000
            val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
            ctx.contentResolver.query(col, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.MIME_TYPE),
                "${MediaStore.Images.Media.DATE_ADDED} > ? AND $pathCol LIKE ?", arrayOf((now - WINDOW_S).toString(), "%Screenshot%"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                if (!c.moveToFirst()) return
                val id = c.getLong(0)
                if (prefs.getLong(LAST, -1) == id) return
                prefs.edit().putLong(LAST, id).apply()
                val uri = ContentUris.withAppendedId(col, id)
                add(uri, c.getLong(1) * 1000, ClipDescription("Скриншот", arrayOf(c.getString(2) ?: "image/png")))
            }
        } catch (e: Throwable) { }
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
