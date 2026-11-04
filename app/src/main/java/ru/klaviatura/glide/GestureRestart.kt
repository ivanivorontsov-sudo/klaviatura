package ru.klaviatura.glide

import kotlin.math.sqrt

/** mid-gesture correction: where the "real" gesture starts inside a swipe path */
object GestureRestart {
    /**
     * (a) hold: finger kept within [still] key widths for >= [holdMs] => everything before the hold end is discarded.
     * Returns the index of the last such hold (start of the new segment) or 0.
     * Double letters are paused on for ~100-300 ms, the default 450 ms stays above that.
     */
    fun holdStart(xs: IntArray, ys: IntArray, times: IntArray, n: Int, kw: Float, holdMs: Int, still: Float = 0.15f): Int {
        if (holdMs < MIN_HOLD_MS || n < 3) return 0
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
        // 1.3.1: much more conservative — only a clear retrace back onto the first key after a long path
        if (n < 12) return emptyList()
        val out = ArrayList<Int>()
        var left = false; var travelled = 0f; var armed = false
        for (i in 1 until n - 2) {
            travelled += d(xs, ys, i - 1, i)
            if (!left && (if (armed) d(xs, ys, 0, i) > 1.5f * kw && travelled > 2f * kw else d(xs, ys, 0, i) > 3f * kw && travelled > 5f * kw)) left = true
            if (left && d(xs, ys, 0, i) < 0.4f * kw) {
                // reversal: incoming and outgoing directions point opposite-ish
                val j = maxOf(0, i - 3); val k = minOf(n - 1, i + 3)
                val ax = (xs[i] - xs[j]).toFloat(); val ay = (ys[i] - ys[j]).toFloat()
                val bx = (xs[k] - xs[i]).toFloat(); val by = (ys[k] - ys[i]).toFloat()
                val la = sqrt(ax * ax + ay * ay); val lb = sqrt(bx * bx + by * by)
                if (la > 0 && lb > 0 && (ax * bx + ay * by) / (la * lb) < -0.2f) {
                    // start the new segment at the point closest to the first key
                    var b = i; for (m in i + 1..minOf(n - 1, i + 6)) if (d(xs, ys, 0, m) < d(xs, ys, 0, b)) b = m
                    out.add(b); left = false; travelled = 0f; armed = true }
            }
        }
        return out
    }

    /** cost margin the segment decode must win by */
    const val SEGMENT_MARGIN = 1.4
    /** below this the restart is off (slider «Выкл») */
    const val MIN_HOLD_MS = 500
    const val DEFAULT_HOLD_MS = 1000

    private fun d(xs: IntArray, ys: IntArray, a: Int, b: Int): Float {
        val dx = (xs[a] - xs[b]).toFloat(); val dy = (ys[a] - ys[b]).toFloat(); return sqrt(dx * dx + dy * dy)
    }
}

/** proper-noun casing (OpenCorpora Name/Surn/Patr/Geox/Orgn/Trad forms with no common-noun reading) */
object ProperNouns {
    @Volatile var forms: Set<String> = emptySet()

    /** names that are also common words: capitalised only when the previous word points to a person */
    private val AMBIGUOUS: Set<String> = listOf(
        "вера", "веры", "вере", "веру", "верой", "надежда", "надежды", "надежде", "надежду", "надеждой",
        "любовь", "любови", "любовью", "люба", "любе", "любу", "роман", "романа", "роману", "романом", "романе",
        "лев", "льва", "льву", "львом", "льве", "роза", "розы", "розе", "розу", "розой", "слава", "славы", "славе", "славу", "славой",
        "мила", "милы", "миле", "милу", "лиля", "лили", "лиле", "лилю", "майя", "майи", "майе", "майю", "марс", "заря", "зари", "заре", "зарю",
        "победа", "лада", "лады", "ладе", "ладу", "ника", "ники", "нике", "нику", "сабина", "ангелина", "дина", "дины", "дине", "дину",
        "глеб", "лён", "ян", "яна", "яне", "яну", "ада", "ады", "аде", "аду", "алла", "аллы", "алле", "аллу", "алиса", "алисы", "алисе", "алису"
    ).toHashSet()
    private val CUES: Set<String> = listOf(
        "привет", "приветик", "здравствуй", "здравствуйте", "дорогая", "дорогой", "дорогую", "милая", "милый", "тётя", "тетя", "дядя",
        "бабушка", "дедушка", "баба", "деда", "сестра", "брат", "подруга", "подруге", "друг", "другу", "мама", "папа", "зовут", "звали",
        "позвони", "позвонить", "напиши", "написать", "скажи", "передай", "спроси", "спросить", "попроси",
        "тёте", "тете", "дяде", "сестре", "брату", "сестры", "брата", "жена", "жене", "муж", "мужу", "дочь", "дочке", "сын", "сыну", "коллега", "коллеге",
        "пока", "спасибо", "благодарю", "уважаемая", "уважаемый", "товарищ", "имени", "имя"
    ).toHashSet()

    /** @param prev the previous word (any case) or null; a capitalised unambiguous name right before also counts («Анна Роза») */
    fun case(w: String, prev: String? = null): String {
        if (w.isEmpty() || w[0].isUpperCase()) return w
        val l = w.lowercase()
        if (l in forms) return w.replaceFirstChar { it.uppercaseChar() }
        if (l in AMBIGUOUS && prev != null) {
            val p = prev.lowercase().trim { !it.isLetter() }
            val nameBefore = prev.firstOrNull()?.isUpperCase() == true && p in forms
            val cue = p in CUES // prepositions are no cue: «с верой в победу», «к славе»
            if (cue || nameBefore) return w.replaceFirstChar { it.uppercaseChar() }
        }
        // hyphenated: «санкт-петербурге», «нью-йорк»
        if ('-' in w) {
            val parts = w.split('-')
            if (parts.any { it.lowercase() in forms }) return parts.joinToString("-") { if (it.length > 1) it.replaceFirstChar { c -> c.uppercaseChar() } else it }
        }
        return w
    }
}
