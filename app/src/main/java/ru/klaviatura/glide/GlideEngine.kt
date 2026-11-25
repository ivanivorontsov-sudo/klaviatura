// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.glide

import helium314.keyboard.latin.utils.prefs
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
    private val lexicons = java.util.concurrent.ConcurrentHashMap<String, Lexicon>()
    private val loading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val unigrams = ConcurrentHashMap<String, Int>()
    private val bigrams = ConcurrentHashMap<String, Int>()
    private val trigrams = ConcurrentHashMap<String, Int>()
    private val forgotten = ConcurrentHashMap.newKeySet<String>()
    @Volatile override var biasX = 0f; private set
    @Volatile override var biasY = 0f; private set
    private var lastDecoder: GlideDecoder? = null
    private var lastCandidates: List<GlideCandidate> = emptyList()
    @Volatile private var dirty = false

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        ru.klaviatura.code.SyntaxPackManager.init(context)
        ru.klaviatura.emoji.EmojiSuggest.init(context)
        ru.klaviatura.text.Profanity.init(context)
        ru.klaviatura.text.Yo.init(context)
        thread(name = "kl-glide-load") {
            for (lang in listOf("ru", "en", ru.klaviatura.code.CodeMode.LEX_LANG)) { try { loadLexicon(lang); if (lang != ru.klaviatura.code.CodeMode.LEX_LANG) loadLm(lang) } catch (e: Throwable) { } }
            try { ProperNouns.forms = appContext!!.assets.open("glide/ru_proper.txt").bufferedReader().useLines { it.toHashSet() } } catch (e: Exception) { }
            load()
        }
    }

    const val PREF_HOLD_MS = "kl_swipe_hold_restart_ms"
    @Volatile var holdMs = GestureRestart.DEFAULT_HOLD_MS
    /** called (on the gesture thread) when a hold restart happens: haptic / trail flash */
    @Volatile var onRestart: (() -> Unit)? = null
    private var lastLoopStart = 0
    private var lastHoldStart = 0
    val lms = java.util.concurrent.ConcurrentHashMap<String, BigramLM>()
    private fun loadLm(lang: String) {
        val ctx = appContext ?: return
        try { lms[lang] = ctx.assets.open("glide/${lang}_bigrams.tsv").bufferedReader().useLines { BigramLM.parse(it) } } catch (e: Exception) { }
    }

    private val HELP_FORMS = setOf("помощь", "помощи", "помощью", "помощей", "помощам", "помощами", "помощах")
    private val CURATED_NEXT_WORDS = mapOf(
        // High-confidence phrase repairs for Russian next-word predictions.
        "гимн" to listOf("россии"),
        "обороны" to listOf("россии"),
        "помощь" to listOf("россии"),
        "помощи" to listOf("россии"),
        "помощью" to listOf("россии"),
        "помощей" to listOf("россии"),
        "помощам" to listOf("россии"),
        "помощами" to listOf("россии"),
        "помощах" to listOf("россии"),
        "привет" to listOf("как"),
        "спиртовой" to listOf("салфеткой", "салфетка"),
        "спиртовая" to listOf("салфетка", "салфеткой"),
        "спиртовые" to listOf("салфетки", "салфетками"),
        "спиртовыми" to listOf("салфетками", "салфетки")
    )
    private val RU_SPECIAL_WORDS = """спиртовой спиртового спиртовому спиртовым спиртовом спиртовая спиртовой спиртовую спиртовою спиртовое спиртовые спиртовых спиртовым спиртовыми салфетка салфетки салфетке салфетку салфеткой салфеткою салфеток салфеткам салфетками салфетках
        подтверждениями подтверждение подтверждения подтверждению подтверждении подтверждений подтвердить подтверди подтвержди подтверждаю подтверждаешь подтверждает подтверждаем подтверждаете подтверждают
        сверить сверь сверка сверку сверке сверкой сверил сверила сверили сверяю сверяешь сверяет сверяем сверяйте
        рост росте роста росту ростом росты ростов
        перейти перейди перейдите перейдём перейду перейдёт перейдут перейдя переход перехода переходе переходу
        поищи поищите поиск поиски поиском поисков поисковый поисковая ии""".split(Regex("\\s+")).toSet()

    /** Keep unsolicited Russian next-word predictions free of Ukraine-related corpus noise. */
    internal fun isUkraineRelatedPrediction(word: String): Boolean {
        val normalized = word.lowercase(Locale.ROOT).replace('ё', 'е')
        return normalized.contains("украин") || normalized.contains("україн")
    }

    /** Hide contextually absurd greeting continuations without banning the words from normal typing. */
    internal fun shouldSuppressNextWordPrediction(previous: String?, next: String): Boolean {
        val p = previous?.lowercase(Locale.ROOT)?.replace('ё', 'е')
        val w = next.lowercase(Locale.ROOT).replace('ё', 'е')
        return isUkraineRelatedPrediction(w) || (p == "привет" && w == "писает")
    }

    /** Prevent a spurious corpus association from turning «помощь» into an unwanted political continuation. */
    internal fun isUnwantedHelpUkrainePair(previous: String, next: String): Boolean {
        val p = previous.lowercase(Locale.ROOT).replace('ё', 'е')
        val w = next.lowercase(Locale.ROOT).replace('ё', 'е')
        return p in HELP_FORMS && w == "украине"
    }

    /** next-word predictions: curated common phrases, then user bigrams, then the base LM */
    fun predictNext(prev: String, n: Int = 3, previousPrevious: String? = null): List<String> {
        val p = prev.lowercase(Locale.ROOT).replace('ё', 'е')
        val p2 = previousPrevious?.lowercase(Locale.ROOT)?.replace('ё', 'е')
        val curated = CURATED_NEXT_WORDS[p].orEmpty()
        val tri = if (p2 == null) emptyList() else trigrams.entries
            .filter { it.key.startsWith("$p2\t$p\t") }
            .sortedByDescending { it.value }
            .map { it.key.substringAfterLast('\t') }
        val user = bigrams.entries.filter { it.key.startsWith("$p\t") }.sortedByDescending { it.value }.map { it.key.substringAfter('\t') }
        val base = lms.values.flatMap { it.next(p, n) }
        return (curated + tri + user + base).filter { !isForgotten(it) && !isUkraineRelatedPrediction(it) && !isUnwantedHelpUkrainePair(p, it) }.distinct().take(n)
    }
    private fun loadLexicon(lang: String): Lexicon? {
        lexicons[lang]?.let { return it }
        val ctx = appContext ?: return null
        return try {
            val lex = ctx.assets.open("glide/$lang.tsv").bufferedReader().useLines { Lexicon.parse(lang, it) }
            // Domain vocabulary absent from frequency corpora: make these forms available to swipe typing.
            if (lang == "ru") RU_SPECIAL_WORDS.forEach { lex.addOrBoost(it, 8_000L) }
            if (lang == "en") {
                ctx.assets.open("glide/en_extra.tsv").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0) lex.addOrBoost(line.substring(0, tab), line.substring(tab + 1).toLongOrNull() ?: 1L)
                    }
                }
            }
            ru.klaviatura.code.SyntaxPackManager.applyLexicons(lang, lex)
            lexicons[lang] = lex
            lex
        } catch (e: Throwable) { null }
    }

    /** never blocks the input thread: returns null and loads in background if not ready */
    private fun lexiconIfReady(lang: String): Lexicon? {
        lexicons[lang]?.let { return it }
        if (appContext != null && loading.add(lang)) thread(name = "kl-glide-lex-$lang") { loadLexicon(lang) }
        return null
    }

    fun shouldHandle(): Boolean {
        val ctx = appContext ?: return false
        return !JniUtils.sHaveGestureLib || ctx.prefs().getBoolean(PREF_OWN_GLIDE, true)
    }

    /** next-word predictions; curated phrase repairs outrank noisy corpus n-grams. */
    fun predictions(ngram: NgramContext): List<SuggestedWordInfo> {
        val prev = ngram.getNthPrevWord(1)?.toString() ?: return emptyList()
        val normalizedPrev = prev.lowercase(Locale.ROOT).replace('ё', 'е')
        val curated = CURATED_NEXT_WORDS[normalizedPrev].orEmpty().toSet()
        val prev2 = ngram.getNthPrevWord(2)?.toString()
        return predictNext(prev, 3, prev2).mapIndexed { i, w ->
            val score = if (w in curated) Int.MAX_VALUE - i else 100 - i
            SuggestedWordInfo(
                ru.klaviatura.text.Yo.fix(properCaseSuggestion(w, prev)),
                "", score, SuggestedWordInfo.KIND_PREDICTION,
                Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX,
                SuggestedWordInfo.NOT_A_CONFIDENCE
            )
        }
    }

    /** Apply Russian proper-name casing consistently to native and custom suggestions. */
    fun properCaseSuggestion(word: String, previousWord: String? = null): String {
        val cased = ProperNouns.case(word, previousWord)
        if (cased != word) return cased
        // The proper-name asset is loaded asynchronously; keep this high-confidence correction
        // stable even during the short interval before that list is ready.
        return when (word.lowercase(Locale.ROOT).replace('ё', 'е')) {
            "россии" -> "России"
            "ии" -> "ИИ"
            "samsung" -> "Samsung"
            "xiaomi" -> "Xiaomi"
            "huawei" -> "Huawei"
            "honor" -> "HONOR"
            "oneplus" -> "OnePlus"
            "oppo" -> "OPPO"
            "vivo" -> "vivo"
            "motorola" -> "Motorola"
            "google" -> "Google"
            "realme" -> "realme"
            "iphone" -> "iPhone"
            "ipad" -> "iPad"
            "ios" -> "iOS"
            "android" -> "Android"
            "tiktok" -> "TikTok"
            "telegram" -> "Telegram"
            "whatsapp" -> "WhatsApp"
            "youtube" -> "YouTube"
            "instagram" -> "Instagram"
            "github" -> "GitHub"
            "grok" -> "Grok"
            "chatgpt" -> "ChatGPT"
            "openai" -> "OpenAI"
            "windows" -> "Windows"
            "linux" -> "Linux"
            "debian" -> "Debian"
            "chrome" -> "Chrome"
            "firefox" -> "Firefox"
            "vivaldi" -> "Vivaldi"
            "bluetooth" -> "Bluetooth"
            "wifi" -> "Wi-Fi"
            else -> word
        }
    }

    // ---- 1.5.9: stale-gesture cancellation and time budget ----
    private val requestEpoch = java.util.concurrent.atomic.AtomicInteger(0)
    /** called for every gesture update / end that is queued for decoding; older decodes see they are stale */
    fun newRequest(): Int = requestEpoch.incrementAndGet()
    fun isStale(id: Int): Boolean = requestEpoch.get() != id
    /** time budget of one decode: live updates must stay snappy, the final result may take a bit longer */
    const val BUDGET_UPDATE_MS = 30L
    const val BUDGET_TAIL_MS = 50L
    @Volatile var lastWasStale = false; private set
    @Volatile var lastTruncated = false; private set
    @Volatile var lastDecodeMs = 0L; private set
    @Volatile private var cachedDecoder: GlideDecoder? = null

    @JvmOverloads
    fun suggest(keyboard: Keyboard, data: ComposedData, ngram: NgramContext, locales: List<Locale>, tail: Boolean = true): List<SuggestedWordInfo> {
        val t0 = System.nanoTime()
        val myEpoch = requestEpoch.get()
        val deadline = t0 + (if (tail) BUDGET_TAIL_MS else BUDGET_UPDATE_MS) * 1_000_000L
        val abort: (() -> Boolean)? = if (tail) null else ({ requestEpoch.get() != myEpoch })
        lastWasStale = false; lastTruncated = false
        val keys = keyboard.sortedKeys.mapNotNull { k ->
            val c = k.code
            if (c <= 0 || !Character.isLetter(c)) null
            else GKey(Character.toLowerCase(c.toChar()), k.x + k.width / 2f, k.y + k.height / 2f, k.width.toFloat(), k.height.toFloat())
        }
        if (keys.isEmpty()) return emptyList()
        val main = keyboard.mId.locale.language
        lastMainLang = main
        // English brands and loanwords must remain swipable on the Russian ЙЦУКЕН layout,
        // even when the user has not enabled a separate English subtype.
        val extraCrossLanguages = if (main == "ru") listOf("en") else emptyList()
        val langs = (listOf(main) + locales.map { it.language } + extraCrossLanguages).distinct()
        val lex = langs.mapIndexedNotNull { i, l -> lexiconIfReady(l)?.let { it to (if (i == 0) 0.0 else 0.3) } }.toMutableList()
        val codeMode = ru.klaviatura.code.CodeMode.isCodeLayout(keyboard)
        ru.klaviatura.code.CodeMode.active = codeMode
        if (codeMode) {
            lexiconIfReady(ru.klaviatura.code.CodeMode.LEX_LANG)?.let { codeLex ->
                lex.removeAll { it.first.lang == ru.klaviatura.code.CodeMode.LEX_LANG }
                lex.add(0, codeLex to 0.0)
            }
        }
        if (lex.isEmpty()) return emptyList()
        // the decoder (key tables, letter-code maps) is reused while the layout stays the same
        val decoder = cachedDecoder?.takeIf { it.keys == keys } ?: GlideDecoder(keys).also { cachedDecoder = it }
        val p = data.mInputPointers
        val prevRaw = ngram.getNthPrevWord(1)?.toString()
        lastPreviousPrevious = ngram.getNthPrevWord(2)?.toString()?.lowercase(Locale.ROOT)?.replace('ё', 'е')
        val prev = prevRaw?.lowercase()
        holdMs = appContext?.let { it.prefs().getInt(PREF_HOLD_MS, GestureRestart.DEFAULT_HOLD_MS) } ?: GestureRestart.DEFAULT_HOLD_MS
        if (p.pointerSize < 8) lastLoopStart = 0
        var xs = p.xCoordinates; var ys = p.yCoordinates; var n = p.pointerSize
        // (a) hold-to-restart: drop everything before the last long still point
        val hs = GestureRestart.holdStart(xs, ys, p.times, n, decoder.kw, holdMs)
        if (hs > 0) {
            xs = xs.copyOfRange(hs, n); ys = ys.copyOfRange(hs, n); n -= hs
            if (hs != lastHoldStart) { lastHoldStart = hs; onRestart?.invoke() }
        } else lastHoldStart = 0
        var res = decoder.decode(xs, ys, n, lex, this, prev, deadlineNs = deadline, abort = abort)
        if (decoder.lastAborted) { lastWasStale = true; return emptyList() }
        var truncated = decoder.lastTruncated
        // (b) returned to the start and went again: decode the latest segment, keep it only if clearly better
        for (st in (if (holdMs >= GestureRestart.MIN_HOLD_MS) GestureRestart.returnStarts(xs, ys, n, decoder.kw) else emptyList()).asReversed()) {
            if (System.nanoTime() > deadline) break
            val seg = decoder.decode(xs.copyOfRange(st, n), ys.copyOfRange(st, n), n - st, lex, this, prev, deadlineNs = deadline, abort = abort)
            if (decoder.lastAborted) { lastWasStale = true; return emptyList() }
            truncated = truncated || decoder.lastTruncated
            if (seg.isNotEmpty() && (res.isEmpty() || seg[0].cost < res[0].cost - GestureRestart.SEGMENT_MARGIN)) {
                if (st != lastLoopStart) { lastLoopStart = st; onRestart?.invoke() }
                res = seg; xs = xs.copyOfRange(st, n); ys = ys.copyOfRange(st, n); n -= st; break
            }
        }
        val guess = decoder.rawGuess(xs, ys, n)
        val all = if (guess != null && !isForgotten(guess) && res.none { it.word == guess })
            res.toMutableList().apply { add(minOf(3, size), GlideCandidate(guess, main, (res.getOrNull(minOf(3, size) - 1)?.cost ?: 0.0) + 0.01, false)) } else res
        val extra = ArrayList<GlideCandidate>()
        if (codeMode) {
            val codeLex = lexicons[ru.klaviatura.code.CodeMode.LEX_LANG]
            val topWord = all.firstOrNull()?.word
            if (topWord != null) {
                for (s in ru.klaviatura.code.CodeSwipe.splitCompound(topWord, codeLex)) {
                    if (all.none { it.word == s } && extra.none { it.word == s })
                        extra.add(GlideCandidate(s, ru.klaviatura.code.CodeMode.LEX_LANG, (all.getOrNull(0)?.cost ?: 0.0) + 0.02, false))
                }
            }
            for (s in ru.klaviatura.code.CodeSwipe.joinWithPrevious(prev, topWord ?: guess ?: "")) {
                if (all.none { it.word == s } && extra.none { it.word == s })
                    extra.add(GlideCandidate(s, ru.klaviatura.code.CodeMode.LEX_LANG, (all.getOrNull(0)?.cost ?: 0.0) - 0.05, false))
            }
        }
        val merged = if (extra.isEmpty()) all else (extra + all)
        lastDecoder = decoder; lastCandidates = merged // picking the guess teaches the word (see learn)
        lastTruncated = truncated; lastDecodeMs = (System.nanoTime() - t0) / 1_000_000L
        return merged.mapIndexed { i, c ->
            val score = (1_000_000 - c.cost * 50_000).toInt().coerceAtLeast(1)
            var kind = SuggestedWordInfo.KIND_CORRECTION
            if (i == 0) kind = kind or SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION
            SuggestedWordInfo(ru.klaviatura.text.Yo.fix(properCaseSuggestion(c.word, prevRaw)), "", score, kind, Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
        }
    }

    /**
     * Apply newly installed/updated lexicon packs without throwing away the live dictionaries.
     *
     * Called by the settings screen on Dispatchers.IO. A live lexicon is guarded by the same
     * monitor used by GlideDecoder while it scans, and this method returns only after the
     * active Russian/English packs are available for swipe input.
     */
    fun reloadLexicons() {
        val languages = (lexicons.keys.toList() + listOf(lastMainLang, "ru", "en")).distinct()
        languages.forEach { lang ->
            runCatching {
                val lexicon = lexicons[lang] ?: loadLexicon(lang)
                if (lexicon != null && lexicons[lang] === lexicon) {
                    synchronized(lexicon) {
                        ru.klaviatura.code.SyntaxPackManager.applyLexicons(lang, lexicon)
                    }
                }
            }
        }
    }

    /** languages with Cyrillic script (for learning new words into the right lexicon) */
    private val CYR = setOf("ru", "uk", "bg", "be", "sr", "mk", "kk")
    @Volatile private var lastMainLang = "ru"
    @Volatile private var lastPreviousPrevious: String? = null
    private fun learnLang(word: String): String {
        val cyr = word.any { it in '\u0400'..'\u04FF' }
        val main = lastMainLang
        return if (cyr) (if (main in CYR) main else "ru") else if (main !in CYR && main != "el") main else "en"
    }

    /** start loading the lexicons of the enabled languages in the background (so the first swipe already works) */
    fun preload(langs: Collection<String>) { for (l in langs) lexiconIfReady(l) }

    // ---- learning (local only) ----
    override fun wordBoost(word: String): Double = unigrams[word]?.let { 0.25 * ln(1.0 + it) } ?: 0.0
    override fun bigramBoost(prev: String?, word: String): Double =
        if (prev == null || isUnwantedHelpUkrainePair(prev, word)) 0.0 else {
            val p = prev.lowercase(Locale.ROOT).replace('ё', 'е')
            val w = word.lowercase(Locale.ROOT).replace('ё', 'е')
            val p2 = lastPreviousPrevious
            val tri = if (p2 == null) 0.0 else
                trigrams["$p2\t$p\t$w"]?.let { 0.45 * ln(1.0 + it) } ?: 0.0
            (bigrams["$p\t$w"]?.let { 0.35 * ln(1.0 + it) } ?: 0.0) +
                tri + GlideDecoder.CTX_W * (lms.values.maxOfOrNull { it.score(p, w) } ?: 0.0)
        }
    override fun isForgotten(word: String) = forgotten.contains(word) || ru.klaviatura.text.Profanity.blocks(word)

    /** called for every committed word (typed, swiped or picked from suggestions) */
    fun learn(rawWord: String, ngram: NgramContext) {
        val word = rawWord.lowercase().let { ru.klaviatura.text.Yo.map[it] ?: it }
        if (word.isEmpty() || word.any { !Character.isLetter(it) && it != '\'' && it != '-' }) return
        if (ru.klaviatura.text.Profanity.blocks(word)) return // learned only when the filter is off
        forgotten.remove(word)
        unigrams.merge(word, 1, Int::plus)
        val previous = ngram.getNthPrevWord(1)?.toString()?.lowercase(Locale.ROOT)?.replace('ё', 'е')
        previous?.let { bigrams.merge("$it\t$word", 1, Int::plus) }
        val previousPrevious = ngram.getNthPrevWord(2)?.toString()?.lowercase(Locale.ROOT)?.replace('ё', 'е')
        if (previousPrevious != null && previous != null)
            trigrams.merge("$previousPrevious\t$previous\t$word", 1, Int::plus)
        // new words become swipeable in the matching script
        lexicons[learnLang(word)]?.let { if (!it.contains(word)) synchronized(it) { it.add(word, 1) } }
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
        trigrams.keys.removeIf { it.endsWith("\t$word") }
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
        trigrams.forEach { (w, c) -> append("t\t$w\t$c\n") }
        forgotten.forEach { append("f\t$it\n") }
    }

    fun import(text: String, replace: Boolean) {
        if (replace) { unigrams.clear(); bigrams.clear(); trigrams.clear(); forgotten.clear() }
        text.lineSequence().forEach { line ->
            val p = line.split('\t')
            when (p.firstOrNull()) {
                "bias" -> if (p.size >= 3) { biasX = p[1].toFloatOrNull() ?: 0f; biasY = p[2].toFloatOrNull() ?: 0f }
                "u" -> if (p.size >= 3) unigrams.merge(p[1], p[2].toIntOrNull() ?: 1, Int::plus)
                "b" -> if (p.size >= 4) bigrams.merge(p[1] + "\t" + p[2], p[3].toIntOrNull() ?: 1, Int::plus)
                "t" -> if (p.size >= 5) trigrams.merge(p[1] + "\t" + p[2] + "\t" + p[3], p[4].toIntOrNull() ?: 1, Int::plus)
                "f" -> if (p.size >= 2) forgotten.add(p[1])
            }
        }
        unigrams.keys.forEach { w ->
            lexicons[learnLang(w)]?.let { if (!it.contains(w)) synchronized(it) { it.add(w, 1) } }
        }
        save()
    }

    private fun load() { file()?.takeIf { it.isFile }?.let { import(it.readText(), true) } }

    val learnedWordCount get() = unigrams.size
}
