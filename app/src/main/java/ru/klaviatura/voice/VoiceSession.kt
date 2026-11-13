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
) {
    var lastPartial = ""; private set
    var lastSpeechAt = System.currentTimeMillis(); private set

    fun feed(buf: ShortArray, n: Int) {
        var done = false
        for ((_, r) in asrs) if (r.accept(buf, n)) done = true
        if (done) finishPhrase(false)
        else {
            val (lang, r) = asrs[0]
            val p = VoiceText.format(JSONObject(r.partial()).optString("partial"), lang, commands = false)
            if (p.isNotBlank()) lastSpeechAt = System.currentTimeMillis()
            if (p != lastPartial) { lastPartial = p; out.partial(p) }
        }
    }

    fun replace(newAsrs: List<Pair<String, Asr>>) { finishPhrase(true); asrs.forEach { it.second.close() }; asrs = newAsrs }

    fun finish() { finishPhrase(true) }
    fun close() { asrs.forEach { try { it.second.close() } catch (_: Exception) {} } }

    /** picks the best recognizer's result (mixed mode: highest confidence) and commits it */
    fun finishPhrase(final: Boolean): Boolean {
        var best: List<String>? = null; var bestLang = ""; var bestConf = -1.0
        for ((l, r) in asrs) {
            val js = JSONObject(if (final) r.final() else r.result())
            val arr = js.optJSONArray("result")
            var words: List<String>
            var conf = 0.5
            if (arr != null && arr.length() > 0) {
                // drop low-confidence words (typical noise hallucinations like «ты», «и»)
                val ws = ArrayList<String>(); var sum = 0.0
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i); val c = o.optDouble("conf", 1.0); sum += c
                    if (c >= MIN_CONF) ws.add(o.optString("word"))
                }
                words = ws; conf = sum / arr.length()
            } else words = js.optString("text").split(' ').filter { it.isNotBlank() }
            if (words.isEmpty()) continue
            if (l == asrs[0].first) conf += 0.05
            if (conf > bestConf) { bestConf = conf; best = words; bestLang = l }
        }
        val words = best
        if (words == null) { if (lastPartial.isNotEmpty()) { lastPartial = ""; out.partial("") }; return false }
        lastSpeechAt = System.currentTimeMillis()
        val text = VoiceText.format(words.joinToString(" "), bestLang.substringBefore('-'), commands)
        lastPartial = ""
        if (text.isNotEmpty()) out.commit(text) else out.partial("")
        return true
    }

    companion object { const val MIN_CONF = 0.45 }
}

object VoiceText {
    private val NUM = setOf("ноль","один","одна","два","две","три","четыре","пять","шесть","семь","восемь","девять","десять")
    /** Zipformer writes «один-два три-четыре»: split hyphens only between number words */
    fun dehyphenNumbers(t: String) = t.replace(Regex("(\\p{L}+)-(\\p{L}+)")) { m ->
        if (m.groupValues[1] in NUM && m.groupValues[2] in NUM) "${m.groupValues[1]} ${m.groupValues[2]}" else m.value }

    /** keeps only words in the script of the language, collapses repetitions, applies spoken punctuation */
    fun format(raw: String, lang: String, commands: Boolean): String {
        val cyr = lang in setOf("ru", "uk", "be", "bg", "sr")
        var words = raw.trim().split(Regex("\\s+")).filter { w ->
            w.isNotEmpty() && w.all { ch -> !ch.isLetter() || (if (cyr) ch in '\u0400'..'\u04FF' else ch !in '\u0400'..'\u04FF') }
        }
        words = dedupe(words)
        words = words.map { ru.klaviatura.text.Yo.fix(ru.klaviatura.text.Profanity.mask(it)) }
        if (commands) words = punctuate(words, lang)
        words = capitalize(words)
        return words.joinToString(" ").replace(Regex(" ([,.!?:;])"), "$1").replace(" \n ", "\n").trim()
    }

    /**
     * Voice partials are cumulative. Once a user edits the text, that spoken prefix is already
     * committed and must not be inserted again when the recognizer sends the full phrase next time.
     */
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
