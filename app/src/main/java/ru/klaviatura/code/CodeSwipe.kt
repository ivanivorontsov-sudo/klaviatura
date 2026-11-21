// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.code

import ru.klaviatura.glide.Lexicon

/**
 * Joins swipe parts into identifiers: my + list → myList / my_list,
 * and splits a single swipe like "mylist" into known tokens.
 */
object CodeSwipe {
    private val ident = Regex("^[A-Za-z_А-Яа-яЁё][A-Za-z0-9_А-Яа-яЁё]*$")

    fun isIdent(s: String?): Boolean = s != null && ident.matches(s)

    fun camel(parts: List<String>): String {
        if (parts.isEmpty()) return ""
        val mapped = parts.map { CodeVoice.toIdentToken(it) }.filter { it.isNotEmpty() }
        if (mapped.isEmpty()) return ""
        return mapped[0].replaceFirstChar { it.lowercaseChar() } +
            mapped.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    }

    fun pascal(parts: List<String>): String =
        parts.map { CodeVoice.toIdentToken(it) }.filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }

    fun snake(parts: List<String>): String =
        parts.map { CodeVoice.toIdentToken(it).lowercase() }.filter { it.isNotEmpty() }.joinToString("_")

    /** Suggestions to append [word] to the already committed identifier [prev]. */
    fun joinWithPrevious(prev: String?, word: String): List<String> {
        if (!isIdent(prev) || !isIdent(word) || word.length < 2) return emptyList()
        val p = prev!!
        return listOf(camel(listOf(p, word)), snake(listOf(p, word)), p + word).distinct()
    }

    /**
     * If [word] is a concatenation of 2–3 lexicon tokens (length ≥ 2), offer camelCase and snake_case.
     * Prefers a split that uses more frequent / longer first tokens.
     */
    fun splitCompound(word: String, lex: Lexicon?, max: Int = 4): List<String> {
        val w = word.lowercase()
        if (w.length < 4 || lex == null) return emptyList()
        val out = ArrayList<String>()
        fun known(s: String) = s.length >= 2 && lex.contains(s)
        for (i in 2 until w.length - 1) {
            val a = w.substring(0, i); val b = w.substring(i)
            if (known(a) && known(b)) {
                out.add(camel(listOf(a, b)))
                out.add(snake(listOf(a, b)))
            }
            if (out.size >= max * 2) break
        }
        if (out.size < 2) {
            for (i in 2 until w.length - 3) for (j in i + 2 until w.length - 1) {
                val a = w.substring(0, i); val b = w.substring(i, j); val c = w.substring(j)
                if (known(a) && known(b) && known(c)) {
                    out.add(camel(listOf(a, b, c)))
                    out.add(snake(listOf(a, b, c)))
                    if (out.size >= max * 2) break
                }
            }
        }
        return out.distinct().take(max)
    }
}
