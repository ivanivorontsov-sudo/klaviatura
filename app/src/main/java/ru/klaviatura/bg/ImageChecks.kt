// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.bg

/** pure checks used by the AI-background capture (unit tested) */
object ImageChecks {
    /** [lum] = size×size luminance 0..255; true for QR/barcode-like binary high-contrast patterns */
    fun qrLike(lum: IntArray, size: Int): Boolean {
        var dark = 0; var light = 0; var trans = 0
        for (y in 0 until size) { var prev = -1
            for (x in 0 until size) {
                val l = lum[y * size + x]
                val cls = if (l < 70) 0 else if (l > 185) 1 else 2
                if (cls == 0) dark++ else if (cls == 1) light++
                if (prev >= 0 && cls != 2 && prev != 2 && cls != prev) trans++
                prev = cls
            }
        }
        val tot = size * size
        return (dark + light) > tot * 0.85 && dark > tot * 0.15 && light > tot * 0.15 && trans > tot * 0.07
    }

    private val BAD_SRC = Regex("(?i)qr|avatar|logo|icon|favicon|emoji|sprite|badge|profile|login|captcha")

    /** URL/alt heuristics: avatars, logos, icons, QR / login images are never backgrounds */
    fun badSource(src: String, alt: String) = BAD_SRC.containsMatchIn(src.substringBefore('?').takeLast(120)) || BAD_SRC.containsMatchIn(alt)

    /** minimal useful size: >= 512 px on the short side */
    fun bigEnough(w: Int, h: Int) = minOf(w, h) >= 512
}
