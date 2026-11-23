// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.voice

import android.content.Context
import java.util.Locale

/**
 * Contextual vocabulary for Russian speech recognition.
 *
 * ASR engines frequently expand spoken initialisms into syllables or ordinary words.
 * This layer is deliberately conservative: it repairs only known, whole-phrase forms,
 * never arbitrary substrings. The list is also supplied as Android recognizer biasing
 * hints on API 33+; offline engines use the same post-processing locally.
 */
object VoiceGlossary {
    private val aliases = linkedMapOf(
        "ркн" to listOf("ркн", "эр ка эн", "эркэн", "эр кэн", "р к н"),
        "vpn" to listOf("vpn", "впн", "ви пи эн", "вэ пэ эн", "вп эн"),
        "бмп" to listOf("бмп", "бэ эм пэ", "бэ эм пе", "б эм п"),
        "бцж" to listOf("бцж", "бэ цэ жэ", "бэ це же", "б ц ж"),
        "api" to listOf("api", "апи", "эй пи ай"),
        "асу" to listOf("асу", "а эс у"),
        "gps" to listOf("gps", "джи пи эс", "гпс"),
        "usb" to listOf("usb", "ю эс би", "юэсби"),
        "wi-fi" to listOf("wi-fi", "вай фай", "вайфай"),
        "bluetooth" to listOf("bluetooth", "блютуз", "блю туз"),
        "ssd" to listOf("ssd", "эс эс ди"),
        "ram" to listOf("ram", "озу", "рэм"),
        "cpu" to listOf("cpu", "цпу", "си пи ю"),
        "gpu" to listOf("gpu", "гпу", "джи пи ю")
    )

    private val phraseMap: Map<String, String> = buildMap {
        aliases.forEach { (canonical, forms) ->
            forms.forEach { form ->
                put(normalizeKey(form), canonical)
            }
        }
    }

    fun normalize(words: List<String>): List<String> {
        if (words.isEmpty()) return words
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            var matched: Pair<String, Int>? = null
            // Prefer the longest exact phrase to avoid partial replacements.
            for (count in 4 downTo 1) {
                if (i + count > words.size) continue
                val phrase = words.subList(i, i + count).joinToString(" ") { clean(it) }
                val canonical = phraseMap[normalizeKey(phrase)]
                if (canonical != null) { matched = canonical to count; break }
            }
            if (matched != null) {
                out.add(matched.first.uppercase(Locale.ROOT))
                i += matched.second
            } else {
                out.add(words[i])
                i++
            }
        }
        return out
    }

    fun biasingPhrases(context: Context): ArrayList<String> {
        val phrases = LinkedHashSet<String>()
        aliases.forEach { (canonical, forms) ->
            phrases.add(canonical.uppercase(Locale.ROOT))
            forms.forEach { phrases.add(it) }
        }
        // User-installed programming vocabulary is local and is never uploaded.
        runCatching {
            ru.klaviatura.code.SyntaxPackManager.spokenKeywords().forEach { (spoken, replacement) ->
                if (spoken.length in 2..48 && replacement.length in 2..48) {
                    phrases.add(spoken)
                    phrases.add(replacement)
                }
            }
        }
        return ArrayList(phrases.take(100))
    }

    private fun clean(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[,.!?;:]+$"), "")
        .replace('ё', 'е')
        .trim()

    private fun normalizeKey(value: String) = clean(value)
        .replace(Regex("[^\\p{L}\\p{N}-]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
