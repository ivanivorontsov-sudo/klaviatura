package ru.klaviatura.glide

import kotlin.math.sqrt

/** mid-gesture correction: where the "real" gesture starts inside a swipe path */
object GestureRestart {
    /**
     * (a) hold: finger kept within [still] key widths for >= [holdMs] => everything before the hold end is discarded.
     * Returns the index of the last such hold (start of the new segment) or 0.
     * Double letters are paused on for ~100-300 ms, the default 450 ms stays above that.
     */
    fun holdStart(xs: IntArray, ys: IntArray, times: IntArray, n: Int, kw: Float, holdMs: Int, still: Float = 0.3f): Int {
        if (holdMs <= 0 || n < 3) return 0
        var result = 0
        // the touch stack samples by distance, so a still finger may produce NO points: the stillness lasts
        // until the first sample that leaves the window, hence the duration is measured up to that sample
        for (a in 0 until n - 1) {
            var e = a
            while (e + 1 < n && d(xs, ys, a, e + 1) <= still * kw) e++
            if (e + 1 < n && times[e + 1] - times[a] >= holdMs) result = maxOf(result, e)
        }
        return result
    }

    /**
     * (b) heuristic restart: after travelling away (> 2 key widths) the path comes back near the starting point
     * with a sharp reversal. Returns candidate indices; the caller decodes both and only takes a segment
     * if it fits clearly better (so words like «потоп» are not cut).
     */
    fun returnStarts(xs: IntArray, ys: IntArray, n: Int, kw: Float): List<Int> {
        if (n < 6) return emptyList()
        val out = ArrayList<Int>()
        var left = false
        for (i in 1 until n - 2) {
            if (!left && d(xs, ys, 0, i) > 2f * kw) left = true
            if (left && d(xs, ys, 0, i) < 0.6f * kw) {
                // reversal: incoming and outgoing directions point opposite-ish
                val j = maxOf(0, i - 3); val k = minOf(n - 1, i + 3)
                val ax = (xs[i] - xs[j]).toFloat(); val ay = (ys[i] - ys[j]).toFloat()
                val bx = (xs[k] - xs[i]).toFloat(); val by = (ys[k] - ys[i]).toFloat()
                val la = sqrt(ax * ax + ay * ay); val lb = sqrt(bx * bx + by * by)
                if (la > 0 && lb > 0 && (ax * bx + ay * by) / (la * lb) < 0.3f) { out.add(i); left = false }
            }
        }
        return out
    }

    /** cost margin the segment decode must win by */
    const val SEGMENT_MARGIN = 0.8

    private fun d(xs: IntArray, ys: IntArray, a: Int, b: Int): Float {
        val dx = (xs[a] - xs[b]).toFloat(); val dy = (ys[a] - ys[b]).toFloat(); return sqrt(dx * dx + dy * dy)
    }
}

/** proper-noun casing (OpenCorpora Name/Surn/Patr/Geox/Orgn/Trad forms with no common-noun reading) */
object ProperNouns {
    @Volatile var forms: Set<String> = emptySet()
    fun case(w: String): String {
        if (w.isEmpty() || w[0].isUpperCase()) return w
        val l = w.lowercase()
        if (l in forms) return w.replaceFirstChar { it.uppercaseChar() }
        // hyphenated: «санкт-петербурге», «нью-йорк»
        if ('-' in w) {
            val parts = w.split('-')
            if (parts.any { it.lowercase() in forms }) return parts.joinToString("-") { if (it.length > 1) it.replaceFirstChar { c -> c.uppercaseChar() } else it }
        }
        return w
    }
}
