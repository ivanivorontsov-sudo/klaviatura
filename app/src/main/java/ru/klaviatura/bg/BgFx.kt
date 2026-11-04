// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.bg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import helium314.keyboard.latin.utils.prefs
import kotlin.math.max
import kotlin.math.min

/** blur + dim for the keyboard background image (soft, eye-friendly) */
object BgFx {
    const val PREF_BLUR = "kl_bg_blur"   // 0..25
    const val PREF_DIM = "kl_bg_dim"     // 0..85 %
    const val DEFAULT_DIM = 30

    @JvmStatic fun apply(context: Context, src: Bitmap?, night: Boolean): Bitmap? {
        if (src == null) return null
        val p = context.prefs()
        val blur = p.getInt(PREF_BLUR, 0).coerceIn(0, 25)
        val dim = p.getInt(PREF_DIM, DEFAULT_DIM).coerceIn(0, 85)
        var bmp = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        if (blur > 0) {
            // downscale -> box blur x3 (≈gaussian) -> upscale, cheap and smooth
            val f = 4
            val small = Bitmap.createScaledBitmap(bmp, max(1, bmp.width / f), max(1, bmp.height / f), true)
            boxBlur(small, max(1, blur / 2)); boxBlur(small, max(1, blur / 2)); boxBlur(small, max(1, blur / 2))
            bmp = Bitmap.createScaledBitmap(small, bmp.width, bmp.height, true)
        }
        if (dim > 0) {
            val a = dim * 255 / 100
            val c = if (night) Color.argb(a, 0x18, 0x14, 0x12) else Color.argb(a, 0xF4, 0xEE, 0xE6)
            Canvas(bmp).drawColor(c)
        }
        return bmp
    }

    private fun boxBlur(b: Bitmap, r: Int) {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        pass(px, tmp, w, h, r, true); pass(tmp, px, w, h, r, false)
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
        val len = if (horiz) w else h; val lines = if (horiz) h else w
        for (l in 0 until lines) {
            var ra = 0; var rr = 0; var rg = 0; var rb = 0
            fun at(i: Int): Int { val c = min(len - 1, max(0, i)); return if (horiz) src[l * w + c] else src[c * w + l] }
            for (i in -r..r) { val c = at(i); ra += c ushr 24; rr += (c shr 16) and 255; rg += (c shr 8) and 255; rb += c and 255 }
            val n = 2 * r + 1
            for (i in 0 until len) {
                val idx = if (horiz) l * w + i else i * w + l
                dst[idx] = ((ra / n) shl 24) or ((rr / n) shl 16) or ((rg / n) shl 8) or (rb / n)
                val o = at(i - r); val nw = at(i + r + 1)
                ra += (nw ushr 24) - (o ushr 24); rr += ((nw shr 16) and 255) - ((o shr 16) and 255)
                rg += ((nw shr 8) and 255) - ((o shr 8) and 255); rb += (nw and 255) - (o and 255)
            }
        }
    }
}
