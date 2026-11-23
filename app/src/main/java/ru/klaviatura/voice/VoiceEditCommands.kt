// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.voice

import java.util.Locale

/** Deterministic offline grammar for editing dictated text. */
object VoiceEditCommands {
    data class Edit(val start: Int, val end: Int, val replacement: String, val description: String)

    private val ordinals = mapOf(
        "первое" to 1, "первый" to 1, "первую" to 1, "один" to 1,
        "второе" to 2, "второй" to 2, "вторую" to 2, "два" to 2,
        "третье" to 3, "третий" to 3, "третью" to 3, "три" to 3,
        "четвертое" to 4, "четвертый" to 4, "четвертую" to 4, "четыре" to 4,
        "пятое" to 5, "пятый" to 5, "пятую" to 5, "пять" to 5,
        "шестое" to 6, "шестой" to 6, "шестую" to 6, "шесть" to 6,
        "седьмое" to 7, "седьмой" to 7, "седьмую" to 7, "семь" to 7,
        "восьмое" to 8, "восьмой" to 8, "восьмую" to 8, "восемь" to 8,
        "девятое" to 9, "девятый" to 9, "девятую" to 9, "девять" to 9,
        "десятое" to 10, "десятый" to 10, "десятую" to 10, "десять" to 10
    )

    fun parse(text: String, spoken: String, allowWholeField: Boolean = true): Edit? {
        val normalized = spoken.trim().trimEnd('.', '!', '?', ',').lowercase(Locale.ROOT)
            .replace('ё', 'е').replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return null

        if (normalized in setOf("удали все", "удали весь текст", "очисти текст", "очисти все")) {
            if (!allowWholeField) return null
            return Edit(0, text.length, "", "Текст очищен")
        }
        if (normalized.startsWith("напиши заново ") || normalized.startsWith("перепиши все ")) {
            if (!allowWholeField) return null
            val replacement = spoken.replace(Regex("(?i)^(напиши заново|перепиши все)\\s*[:,-]?\\s*"), "").trim()
            if (replacement.isEmpty()) return null
            return Edit(0, text.length, replacement, "Текст переписан")
        }

        val ordinalDelete = Regex("^(?:удали|убери)\\s+(\\d+|[а-я]+)(?:\\s+по счету)?\\s+слово$").matchEntire(normalized)
        if (ordinalDelete != null) {
            val n = ordinalDelete.groupValues[1].toIntOrNull() ?: ordinals[ordinalDelete.groupValues[1]] ?: return null
            return wordEdit(text, n, "", "Слово удалено")
        }
        if (normalized in setOf("удали последнее слово", "удали это", "убери последнее слово")) {
            val last = Regex("\\S+").findAll(text).lastOrNull() ?: return null
            return Edit(last.range.first, last.range.last + 1, "", "Последнее слово удалено")
        }
        if (normalized in setOf("удали последнее предложение", "убери последнее предложение")) {
            val end = text.trimEnd().length
            if (end == 0) return null
            val boundary = maxOf(text.lastIndexOf('.', end - 1), text.lastIndexOf('!', end - 1), text.lastIndexOf('?', end - 1))
            return Edit(if (boundary < 0) 0 else boundary + 1, end, "", "Последнее предложение удалено")
        }

        val ordinalReplace = Regex("^(?:исправь|замени)\\s+(\\d+|[а-я]+)(?:\\s+по счету)?\\s+слово\\s+на\\s+(.+)$").matchEntire(normalized)
        if (ordinalReplace != null) {
            val n = ordinalReplace.groupValues[1].toIntOrNull() ?: ordinals[ordinalReplace.groupValues[1]] ?: return null
            val split = spoken.indexOf(" на ", ignoreCase = true)
            val replacement = if (split >= 0) spoken.substring(split + 4).trim().trimEnd('.', '!', '?', ',') else return null
            if (replacement.isEmpty()) return null
            return wordEdit(text, n, replacement, "Слово заменено")
        }

        val replace = Regex("^(?:исправь|замени)\\s+(.+?)\\s+на\\s+(.+)$").matchEntire(normalized)
        if (replace != null) {
            val prefix = Regex("(?i)^(?:исправь|замени)\\s+").find(spoken)?.value?.length ?: return null
            val split = spoken.indexOf(" на ", startIndex = prefix, ignoreCase = true)
            val old = if (split >= 0) spoken.substring(prefix, split).trim() else return null
            val split = spoken.indexOf(" на ", ignoreCase = true)
            val replacement = if (split >= 0) spoken.substring(split + 4).trim().trimEnd('.', '!', '?', ',') else return null
            if (old.isEmpty() || replacement.isEmpty()) return null
            val at = lastIndexOfIgnoreCase(text, old)
            if (at < 0) return null
            return Edit(at, at + old.length, replacement, "Фрагмент исправлен")
        }

        val delete = Regex("^(?:удали|убери)\\s+(.+)$").matchEntire(normalized)
        if (delete != null) {
            val prefix = Regex("(?i)^(?:удали|убери)\\s+").find(spoken)?.value?.length ?: return null
            val phrase = spoken.substring(prefix).trim().trimEnd('.', '!', '?', ',')
            if (phrase.isEmpty()) return null
            val at = lastIndexOfIgnoreCase(text, phrase)
            if (at < 0) return null
            var start = at
            var end = at + phrase.length
            if (end < text.length && text[end].isWhitespace()) end++ else if (start > 0 && text[start - 1].isWhitespace()) start--
            return Edit(start, end, "", "Фрагмент удалён")
        }
        return null
    }

    private fun wordEdit(text: String, ordinal: Int, replacement: String, description: String): Edit? {
        if (ordinal !in 1..100) return null
        val word = Regex("\\S+").findAll(text).drop(ordinal - 1).firstOrNull() ?: return null
        return Edit(word.range.first, word.range.last + 1, replacement, description)
    }

    private fun lastIndexOfIgnoreCase(text: String, needle: String): Int {
        if (needle.isEmpty() || needle.length > text.length) return -1
        for (i in text.length - needle.length downTo 0)
            if (text.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) return i
        return -1
    }
}
