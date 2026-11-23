// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.voice

import org.json.JSONObject

/** one speech recognizer (Vosk on device, Vosk desktop in tests) */
interface Asr {
    fun accept(buf: ShortArray, n: Int): Boolean
    fun result(): String
    fun partial(): String
    fun final(): String
    fun close()
}

/** where text goes: partial = replace the composing text, commit = final text of a phrase */
interface VoiceOutput {
    fun partial(text: String)
    fun commit(text: String)
    /** Handle an explicit edit command instead of inserting it as dictated text. */
    fun command(text: String): Boolean
}

/**
 * Pure pipeline (unit-tested on desktop JVM with the real model): feeds audio to recognizers,
 * shows partials only as *replaceable* composing text, commits only final results, removes
 * repetitions and low-confidence noise words.
 */
class VoiceSession(
    private var asrs: List<Pair<String, Asr>>,
    private val out: VoiceOutput,
    private val commands: Boolean = true,
    private val codeMode: Boolean = false,
) {
    var lastPartial = ""; private set
    var lastSpeechAt = System.currentTimeMillis(); private set

    fun feed(buf: ShortArray, n: Int) {
        var done = false
        for ((_, recognizer) in asrs) if (recognizer.accept(buf, n)) done = true
        if (done) {
            finishPhrase(false)
            return
        }

        // In mixed-language mode, never blindly display the first recognizer.
        // An English model can confidently hallucinate Latin words over Russian speech.
        val best = asrs.mapNotNull { (lang, recognizer) ->
            val raw = runCatching {
                val json = JSONObject(recognizer.partial())
                json.optString("partial").ifBlank { json.optString("text") }
            }.getOrDefault("")
            if (raw.isBlank()) null else {
                val language = lang.substringBefore('-')
                val scriptFit = VoiceText.scriptCompatibility(raw, language)
                val preference = if (lang == asrs.firstOrNull()?.first) 0.015 else 0.0
                Triple(lang, raw, scriptFit * 0.8 + preference)
            }
        }.maxByOrNull { it.third }

        val partial = best?.let { VoiceText.format(it.second, it.first.substringBefore('-'), commands = false) }.orEmpty()
        if (partial.isNotBlank()) lastSpeechAt = System.currentTimeMillis()
        if (partial != lastPartial) {
            lastPartial = partial
            out.partial(partial)
        }
    }

    fun replace(newAsrs: List<Pair<String, Asr>>) {
        finishPhrase(true)
        asrs.forEach { it.second.close() }
        asrs = newAsrs
    }

    fun finish() { finishPhrase(true) }
    fun close() { asrs.forEach { try { it.second.close() } catch (_: Exception) {} } }

    /**
     * Select by confidence *and* language script. Confidence alone is not comparable between
     * independent models: the wrong-language model can be confidently wrong. The script signal
     * lets Russian win for Cyrillic speech and English win for Latin speech in mixed mode.
     */
    fun finishPhrase(final: Boolean): Boolean {
        data class Candidate(val words: List<String>, val lang: String, val score: Double)
        var best: Candidate? = null

        for ((lang, recognizer) in asrs) {
            val json = runCatching { JSONObject(if (final) recognizer.final() else recognizer.result()) }
                .getOrNull() ?: continue
            val resultWords = ArrayList<String>()
            var retainedConfidence = 0.0
            var retainedCount = 0
            val array = json.optJSONArray("result")

            if (array != null && array.length() > 0) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val word = item.optString("word").trim()
                    val confidence = item.optDouble("conf", 1.0).coerceIn(0.0, 1.0)
                    if (word.isNotEmpty() && confidence >= MIN_CONF) {
                        resultWords.add(word)
                        retainedConfidence += confidence
                        retainedCount++
                    }
                }
            }

            if (resultWords.isEmpty()) {
                val fallback = json.optString("text").trim()
                if (fallback.isNotEmpty()) {
                    resultWords.addAll(fallback.split(Regex("\\s+")).filter { it.isNotBlank() })
                    retainedConfidence = 0.5
                    retainedCount = 1
                }
            }
            if (resultWords.isEmpty() || retainedCount == 0) continue

            val raw = resultWords.joinToString(" ")
            val language = lang.substringBefore('-')
            val averageConfidence = retainedConfidence / retainedCount
            val scriptFit = VoiceText.scriptCompatibility(raw, language)
            val primaryPreference = if (lang == asrs.firstOrNull()?.first) 0.015 else 0.0
            val score = averageConfidence + (scriptFit - 0.5) * SCRIPT_WEIGHT + primaryPreference
            val candidate = Candidate(resultWords, lang, score)
            if (best == null || candidate.score > best!!.score) best = candidate
        }

        val selected = best
        if (selected == null) {
            if (lastPartial.isNotEmpty()) {
                lastPartial = ""
                out.partial("")
            }
            return false
        }

        lastSpeechAt = System.currentTimeMillis()
        val language = selected.lang.substringBefore('-')
        var text = VoiceText.format(selected.words.joinToString(" "), language, commands)
        lastPartial = ""
        if (text.isNotEmpty() && commands && out.command(text)) return true
        if (codeMode) text = ru.klaviatura.code.CodeVoice.rewrite(text, language)
        if (text.isNotEmpty()) out.commit(text) else out.partial("")
        return true
    }

    companion object {
        const val MIN_CONF = 0.45
        private const val SCRIPT_WEIGHT = 0.75
    }
}

object VoiceText {
    /**
     * Returns how strongly a candidate matches the alphabet of its recognizer language.
     * This is intentionally independent of word dictionaries so it remains cheap per audio
     * chunk and works for names, technical terms, and phrases absent from the lexicon.
     */
    fun scriptCompatibility(raw: String, language: String): Double {
        val letters = raw.filter { it.isLetter() }
        if (letters.isEmpty()) return 0.5
        val baseLanguage = language.substringBefore('-').lowercase()
        val cyrillicLanguage = baseLanguage in setOf("ru", "uk", "be", "bg", "sr", "mk", "kk", "ky")
        val matching = letters.count { ch ->
            if (cyrillicLanguage) ch in '\\u0400'..'\\u04FF' else ch in 'A'..'Z' || ch in 'a'..'z'
        }
        return matching.toDouble() / letters.length
    }
    private val NUM = setOf("ноль","один","одна","два","две","три","четыре","пять","шесть","семь","восемь","девять","десять")
    /** Zipformer writes «один-два три-четыре»: split hyphens only between number words */
    fun dehyphenNumbers(t: String) = t.replace(Regex("(\\p{L}+)-(\\p{L}+)")) { m ->
        if (m.groupValues[1] in NUM && m.groupValues[2] in NUM) "${m.groupValues[1]} ${m.groupValues[2]}" else m.value }

    /** keeps only words in the script of the language, collapses repetitions, applies spoken punctuation */
    fun format(raw: String, lang: String, commands: Boolean): String {
        val cyr = lang in setOf("ru", "uk", "be", "bg", "sr")
        var words = raw.trim().split(Regex("\\s+")).filter { w ->
            w.isNotEmpty() && (VoiceGlossary.isRecognizedTermToken(w) || w.all { ch -> !ch.isLetter() || (if (cyr) ch in '\u0400'..'\u04FF' else ch !in '\u0400'..'\u04FF') })
        }
        words = dedupe(words)
        // ASR often spells the spoken abbreviation «ИИ» as two separate conjunctions.
        // Merge only the unambiguous phrase «как и и»; ordinary «и и» remains untouched.
        words = mergeContextualInitialisms(words)
        words = repairVoiceInputTerms(words)
        words = VoiceGlossary.normalize(words)
        words = words.map { ru.klaviatura.text.Yo.fix(ru.klaviatura.text.Profanity.mask(it)) }
        if (commands) words = punctuate(words, lang)
        words = capitalize(words)
        return words.joinToString(" ").replace(Regex(" ([,.!?:;])"), "$1").replace(" \n ", "\n").trim()
    }

    /**
     * Voice partials are cumulative. Once a user edits the text, that spoken prefix is already
     * committed and must not be inserted again when the recognizer sends the full phrase next time.
     */
    /** Repairs high-confidence Russian AI initialism patterns without merging ordinary conjunctions. */
    fun mergeContextualInitialisms(words: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            if (i + 2 < words.size && words[i].equals("как", true) &&
                words[i + 1].equals("и", true) && words[i + 2].equals("и", true)) {
                out.add(words[i])
                out.add("ИИ")
                i += 3
            } else {
                // A standalone ASR token «ии» is overwhelmingly the abbreviation, not a word.
                val token = words[i]
                val match = Regex("(?i)^ии([,.!?;:]?)$").matchEntire(token)
                out.add(if (match != null) "ИИ" + match.groupValues[1] else token)
                i++
            }
        }
        return out
    }

    /**
     * Zipformer/Google may hear «ввод» as «вот» or «вод».
     * Repair it only after an explicit voice-related modifier; ordinary «вот» stays intact.
     */
    fun repairVoiceInputTerms(words: List<String>): List<String> {
        val voiceModifiers = setOf("голосовой", "голосовая", "голосовое", "голосового", "голосовой", "голосовым", "голосовом")
        return words.mapIndexed { index, word ->
            val previous = words.getOrNull(index - 1)?.lowercase()?.trimEnd(',', ':', ';') ?: ""
            if (previous in voiceModifiers && word.lowercase().trimEnd(',', '.', '!', '?', ':', ';') in setOf("вот", "вод")) {
                word.replace(Regex("(?i)^(вот|вод)"), "ввод")
            } else word
        }
    }

    fun afterCommittedPrefix(raw: String, committedPrefix: String): String {
        val text = raw.trim()
        val prefix = committedPrefix.trim()
        if (prefix.isEmpty()) return raw
        if (text.startsWith(prefix, ignoreCase = true)
            && (text.length == prefix.length || text[prefix.length].isWhitespace())) {
            return text.substring(prefix.length).trimStart()
        }
        val committedWords = prefix.split(Regex("\\s+")).size
        return text.split(Regex("\\s+")).drop(committedWords).joinToString(" ")
    }

    /** proper nouns (Москвы, Ивану) and the first word after . ! ? or a new line; the very first word is handled by the editor side */
    fun capitalize(words: List<String>): List<String> {
        var start = false
        return words.map { w ->
            var r = ru.klaviatura.glide.ProperNouns.case(w)
            if (start && r.firstOrNull()?.isLetter() == true) r = r.replaceFirstChar { it.uppercaseChar() }
            if (r.isNotEmpty()) start = r.last() in ".!?\n"
            r
        }
    }

    /** «ты ты ты ты» -> «ты»: the same word repeated 3+ times in a row is a recognizer loop, not speech */
    fun dedupe(words: List<String>): List<String> {
        val out = ArrayList<String>(); var i = 0
        while (i < words.size) {
            var j = i; while (j < words.size && words[j].equals(words[i], true)) j++
            val run = j - i
            repeat(if (run >= 3) 1 else run) { out.add(words[i]) }
            i = j
        }
        return out
    }

    fun punctuate(words: List<String>, lang: String): List<String> {
        val cmds = if (lang == "ru") listOf(
            listOf("вопросительный", "знак") to "?", listOf("восклицательный", "знак") to "!",
            listOf("новая", "строка") to "\n", listOf("точка") to ".", listOf("запятая") to ",",
            listOf("двоеточие") to ":", listOf("тире") to " —")
        else listOf(listOf("question", "mark") to "?", listOf("exclamation", "mark") to "!",
            listOf("new", "line") to "\n", listOf("period") to ".", listOf("full", "stop") to ".", listOf("comma") to ",", listOf("colon") to ":")
        val out = ArrayList<String>(); var i = 0
        loop@ while (i < words.size) {
            for ((seq, p) in cmds) if (i + seq.size <= words.size && words.subList(i, i + seq.size).map { it.lowercase() } == seq) {
                out.add(p); i += seq.size; continue@loop
            }
            out.add(words[i]); i++
        }
        return out
    }
}
