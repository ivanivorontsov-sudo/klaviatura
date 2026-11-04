// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.glide

import android.content.Context
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.prefs
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.ln

/** glue between HeliBoard and our own glide decoder; also the local (on-device only) learning store */
object GlideEngine : GlideLearning {
    const val PREF_OWN_GLIDE = "kl_own_glide"
    private var appContext: Context? = null
    private val lexicons = ConcurrentHashMap<String, Lexicon>()
    private val unigrams = ConcurrentHashMap<String, Int>()
    private val bigrams = ConcurrentHashMap<String, Int>()
    private val forgotten = ConcurrentHashMap.newKeySet<String>()
    @Volatile override var biasX = 0f; private set
    @Volatile override var biasY = 0f; private set
    private var lastDecoder: GlideDecoder? = null
    private var lastCandidates: List<GlideCandidate> = emptyList()
    @Volatile private var dirty = false

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        thread(name = "kl-glide-load") {
            for (lang in listOf("ru", "en")) { loadLexicon(lang); loadLm(lang) }
            load()
        }
    }

    val lms = HashMap<String, BigramLM>()
    private fun loadLm(lang: String) {
        val ctx = appContext ?: return
        try { lms[lang] = ctx.assets.open("glide/${lang}_bigrams.tsv").bufferedReader().useLines { BigramLM.parse(it) } } catch (e: Exception) { }
    }

    /** next-word predictions from user bigrams first, then the base LM */
    fun predictNext(prev: String, n: Int = 3): List<String> {
        val p = prev.lowercase()
        val user = bigrams.entries.filter { it.key.startsWith("$p\t") }.sortedByDescending { it.value }.map { it.key.substringAfter('\t') }
        val base = lms.values.flatMap { it.next(p, n) }
        return (user + base).filter { !isForgotten(it) }.distinct().take(n)
    }

    private fun loadLexicon(lang: String): Lexicon? {
        lexicons[lang]?.let { return it }
        val ctx = appContext ?: return null
        return try {
            val lex = ctx.assets.open("glide/$lang.tsv").bufferedReader().useLines { Lexicon.parse(lang, it) }
            lexicons[lang] = lex
            lex
        } catch (e: Exception) { null }
    }

    fun shouldHandle(): Boolean {
        val ctx = appContext ?: return false
        return !JniUtils.sHaveGestureLib || ctx.prefs().getBoolean(PREF_OWN_GLIDE, true)
    }

    /** next-word predictions as suggestion infos (low score, so HeliBoard's own predictions keep priority) */
    fun predictions(ngram: NgramContext): List<SuggestedWordInfo> {
        val prev = ngram.getNthPrevWord(1)?.toString() ?: return emptyList()
        return predictNext(prev, 3).mapIndexed { i, w ->
            SuggestedWordInfo(w, "", 100 - i, SuggestedWordInfo.KIND_PREDICTION, Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
        }
    }

    fun suggest(keyboard: Keyboard, data: ComposedData, ngram: NgramContext, locales: List<Locale>): List<SuggestedWordInfo> {
        val keys = keyboard.sortedKeys.mapNotNull { k ->
            val c = k.code
            if (c <= 0 || !Character.isLetter(c)) null
            else GKey(Character.toLowerCase(c.toChar()), k.x + k.width / 2f, k.y + k.height / 2f, k.width.toFloat(), k.height.toFloat())
        }
        if (keys.isEmpty()) return emptyList()
        val main = keyboard.mId.locale.language
        val langs = (listOf(main) + locales.map { it.language }).distinct()
        val lex = langs.mapIndexedNotNull { i, l -> loadLexicon(l)?.let { it to (if (i == 0) 0.0 else 0.3) } }
        if (lex.isEmpty()) return emptyList()
        val decoder = GlideDecoder(keys)
        val p = data.mInputPointers
        val prev = ngram.getNthPrevWord(1)?.toString()?.lowercase()
        val res = decoder.decode(p.xCoordinates, p.yCoordinates, p.pointerSize, lex, this, prev)
        val guess = decoder.rawGuess(p.xCoordinates, p.yCoordinates, p.pointerSize)
        val all = if (guess != null && res.none { it.word == guess })
            res.toMutableList().apply { add(minOf(3, size), GlideCandidate(guess, main, (res.getOrNull(minOf(3, size) - 1)?.cost ?: 0.0) + 0.01, false)) } else res
        lastDecoder = decoder; lastCandidates = all // picking the guess teaches the word (see learn)
        return all.mapIndexed { i, c ->
            val score = (1_000_000 - c.cost * 50_000).toInt().coerceAtLeast(1)
            var kind = SuggestedWordInfo.KIND_CORRECTION
            if (i == 0) kind = kind or SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION
            SuggestedWordInfo(c.word, "", score, kind, Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
        }
    }

    // ---- learning (local only) ----
    override fun wordBoost(word: String): Double = unigrams[word]?.let { 0.25 * ln(1.0 + it) } ?: 0.0
    override fun bigramBoost(prev: String?, word: String): Double =
        if (prev == null) 0.0 else (bigrams["$prev\t$word"]?.let { 0.35 * ln(1.0 + it) } ?: 0.0) +
            GlideDecoder.CTX_W * (lms.values.maxOfOrNull { it.score(prev, word) } ?: 0.0)
    override fun isForgotten(word: String) = forgotten.contains(word)

    /** called for every committed word (typed, swiped or picked from suggestions) */
    fun learn(rawWord: String, ngram: NgramContext) {
        val word = rawWord.lowercase()
        if (word.isEmpty() || word.any { !Character.isLetter(it) && it != '\'' && it != '-' }) return
        forgotten.remove(word)
        unigrams.merge(word, 1, Int::plus)
        ngram.getNthPrevWord(1)?.toString()?.lowercase()?.let { bigrams.merge("$it\t$word", 1, Int::plus) }
        // new words become swipeable in the matching script
        val lang = if (word.any { it in 'а'..'я' || it == 'ё' }) "ru" else "en"
        lexicons[lang]?.let { if (!it.contains(word)) synchronized(it) { it.add(word, 1) } }
        // adapt to the user's gesture style: average offset of start/end points to the chosen word's keys
        val dec = lastDecoder
        if (dec != null && lastCandidates.any { it.word == word }) {
            val s = dec.lastStart; val e = dec.lastEnd
            val ks = dec.keys.firstOrNull { it.ch == word.first() }; val ke = dec.keys.firstOrNull { it.ch == word.last() }
            if (s != null && e != null && ks != null && ke != null) {
                val dx = ((s.first - ks.cx) + (e.first - ke.cx)) / 2 / dec.kw
                val dy = ((s.second - ks.cy) + (e.second - ke.cy)) / 2 / dec.kh
                biasX = (biasX * 0.9f + (biasX + dx) * 0.1f).coerceIn(-0.4f, 0.4f)
                biasY = (biasY * 0.9f + (biasY + dy) * 0.1f).coerceIn(-0.4f, 0.4f)
            }
            lastCandidates = emptyList()
        }
        scheduleSave()
    }

    fun forget(rawWord: String) {
        val word = rawWord.lowercase()
        unigrams.remove(word); forgotten.add(word)
        bigrams.keys.removeIf { it.endsWith("\t$word") }
        scheduleSave()
    }

    private fun file(): File? = appContext?.let { File(DeviceProtectedUtils.getFilesDir(it), "kl_learn.tsv") }

    private fun scheduleSave() {
        if (dirty) return
        dirty = true
        thread(name = "kl-learn-save") { Thread.sleep(3000); dirty = false; save() }
    }

    @Synchronized fun save() { file()?.writeText(export()) }

    fun export(): String = buildString {
        append("#klaviatura-learn v1\n")
        append("bias\t$biasX\t$biasY\n")
        unigrams.forEach { (w, c) -> append("u\t$w\t$c\n") }
        bigrams.forEach { (w, c) -> append("b\t$w\t$c\n") }
        forgotten.forEach { append("f\t$it\n") }
    }

    fun import(text: String, replace: Boolean) {
        if (replace) { unigrams.clear(); bigrams.clear(); forgotten.clear() }
        text.lineSequence().forEach { line ->
            val p = line.split('\t')
            when (p.firstOrNull()) {
                "bias" -> if (p.size >= 3) { biasX = p[1].toFloatOrNull() ?: 0f; biasY = p[2].toFloatOrNull() ?: 0f }
                "u" -> if (p.size >= 3) unigrams.merge(p[1], p[2].toIntOrNull() ?: 1, Int::plus)
                "b" -> if (p.size >= 4) bigrams.merge(p[1] + "\t" + p[2], p[3].toIntOrNull() ?: 1, Int::plus)
                "f" -> if (p.size >= 2) forgotten.add(p[1])
            }
        }
        unigrams.keys.forEach { w ->
            val lang = if (w.any { it in 'а'..'я' || it == 'ё' }) "ru" else "en"
            lexicons[lang]?.let { if (!it.contains(w)) synchronized(it) { it.add(w, 1) } }
        }
        save()
    }

    private fun load() { file()?.takeIf { it.isFile }?.let { import(it.readText(), true) } }

    val learnedWordCount get() = unigrams.size
}
