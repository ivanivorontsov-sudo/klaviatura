// SPDX-License-Identifier: GPL-3.0-only
// Own open-source gesture (glide) decoder, SHARK2-style template matching.
package ru.klaviatura.glide

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** a letter key on the visible keyboard; coordinates are key centers */
data class GKey(val ch: Char, val cx: Float, val cy: Float, val w: Float, val h: Float)

/**
 * Memory-compact lexicon (1.2.3): all words in one CharArray + offsets, FloatArray frequencies,
 * IntArray open-addressing hash for lookups and per-first-letter IntArray lists.
 * 843k RU forms take ~25 MB instead of ~160 MB with String/HashMap/boxed lists (that OOM'd the IME).
 */
class Lexicon(val lang: String) {
    private var buf = ByteArray(1 shl 16); private var bufLen = 0
    private var off = IntArray(1024); private var lf = FloatArray(1024)
    var size = 0; private set
    private var table = IntArray(2048) { -1 }
    private val firstLists = HashMap<Char, IntList>()
    var maxLog = 1f
    // 1.4.0: own alphabet per lexicon (1 byte per letter for any script: Polish, German, Ukrainian, Greek ...)
    private val b2c = CharArray(256)
    private val fast = IntArray(0x530) // char -> code for Latin/Greek/Cyrillic, 0 = unknown
    private val slow = HashMap<Char, Int>()
    private var nCodes = 0

    class IntList { var a = IntArray(16); var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v } }

    /** word list facade (strings are created on access) */
    val words: List<String> = object : AbstractList<String>() {
        override val size get() = this@Lexicon.size
        override fun get(index: Int) = word(index)
    }
    val logf: FloatArray get() = lf
    val byFirst: Map<Char, IntList> get() = firstLists

    private fun code(c: Char): Int = if (c.code < fast.size) fast[c.code] else slow[c] ?: 0
    private fun codeOrAdd(c: Char): Int {
        code(c).let { if (it != 0) return it }
        if (nCodes >= 255) return 0
        val v = ++nCodes; b2c[v] = c
        if (c.code < fast.size) fast[c.code] = v else slow[c] = v
        return v
    }
    // raw access for the decoder's hot loop (no allocation, no hashing)
    val codeCount: Int get() = nCodes
    fun charOfCode(code: Int): Char = b2c[code]
    fun rawBuf(): ByteArray = buf
    fun rawOffsets(): IntArray = off
    fun rawEnd(): Int = bufLen
    fun word(i: Int): String { val o = off[i]; val l = len(i); return String(CharArray(l) { b2c[buf[o + it].toInt() and 0xFF] }) }
    fun charAt(i: Int, k: Int): Char = b2c[buf[off[i] + k].toInt() and 0xFF]
    fun length(i: Int) = len(i)
    fun firstChar(i: Int) = charAt(i, 0)
    private fun len(i: Int) = (if (i + 1 < size) off[i + 1] else bufLen) - off[i]

    private fun hash(w: CharSequence): Int { var h = 0; for (c in w) h = h * 31 + c.code; return h and 0x7fffffff }
    private fun eq(i: Int, w: CharSequence): Boolean {
        val l = len(i); if (l != w.length) return false
        val o = off[i]; for (k in 0 until l) if ((buf[o + k].toInt() and 0xFF) != code(w[k])) return false; return true
    }
    private fun find(w: CharSequence): Int {
        for (c in w) if (code(c) == 0) return -1
        val m = table.size - 1; var p = hash(w) and m
        while (true) { val v = table[p]; if (v < 0) return -1; if (eq(v, w)) return v; p = (p + 1) and m }
    }
    private fun put(i: Int, w: CharSequence) {
        val m = table.size - 1; var p = hash(w) and m
        while (table[p] >= 0) p = (p + 1) and m
        table[p] = i
    }
    private fun grow() {
        val t = IntArray(table.size * 2) { -1 }; table = t
        for (i in 0 until size) put(i, word(i))
    }

    fun contains(w: String) = find(w) >= 0
    fun indexOf(w: String) = find(w)

    /** Add a domain word, or raise an existing form's frequency so it is not buried by corpus noise. */
    fun addOrBoost(word: String, count: Long) {
        if (word.isEmpty()) return
        val value = ln(1.0 + count).toFloat()
        val existing = find(word)
        if (existing >= 0) {
            if (value > lf[existing]) lf[existing] = value
            maxLog = max(maxLog, value)
        } else {
            add(word, count)
        }
    }

    fun add(word: String, count: Long) {
        if (word.isEmpty() || find(word) >= 0) return
        for (c in word) if (codeOrAdd(c) == 0) return
        val i = size
        if (i + 1 >= off.size) { off = off.copyOf(off.size * 2); lf = lf.copyOf(lf.size * 2) }
        while (bufLen + word.length > buf.size) buf = buf.copyOf(buf.size * 2)
        for (k in word.indices) buf[bufLen + k] = code(word[k]).toByte(); off[i] = bufLen; bufLen += word.length
        val l = ln(1.0 + count).toFloat(); lf[i] = l; maxLog = max(maxLog, l)
        size++
        if (size * 2 > table.size) grow() else put(i, word)
        firstLists.getOrPut(word[0]) { IntList() }.add(i)
    }

    /** release slack after bulk loading */
    fun trim() { buf = buf.copyOf(bufLen); off = off.copyOf(size + 1); lf = lf.copyOf(size + 1)
        firstLists.values.forEach { it.a = it.a.copyOf(it.n) } }

    companion object {
        fun parse(lang: String, lines: Sequence<String>): Lexicon {
            val lex = Lexicon(lang)
            for (line in lines) {
                val t = line.indexOf('\t'); if (t <= 0) continue
                lex.add(line.substring(0, t), line.substring(t + 1).trim().toLongOrNull() ?: 1L)
            }
            lex.trim()
            return lex
        }
    }
}

/** positional mapping between ЙЦУКЕН and QWERTY, used to offer words of the "other" language on the visible layout */
object CrossLayout {
    private val lat = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val cyr = listOf("йцукенгшщз", "фывапролд", "ячсмить")
    val latToCyr = HashMap<Char, Char>().apply { for (r in lat.indices) for (i in lat[r].indices) put(lat[r][i], cyr[r][i]) }
    val cyrToLat = HashMap<Char, Char>().apply { latToCyr.forEach { (k, v) -> put(v, k) } }
}

/** compact base bigram LM (Leipzig news corpora 2023, CC BY) for context reranking and next-word prediction */
class BigramLM {
    // compact: vocabulary ids + sorted LongArray keys (prev<<32 | word) + IntArray counts
    private val vocab = Lexicon("lm")
    private var keys = LongArray(0); private var counts = IntArray(0); private var totals = IntArray(0)
    private var maxLog = 1.0
    private var pk = LongArray(1024); private var pc = IntArray(1024); private var pn = 0

    private fun id(w: String): Int { vocab.add(w, 1); return vocab.indexOf(w) }

    fun add(prev: String, w: String, c: Int) {
        if (pn == pk.size) { pk = pk.copyOf(pn * 2); pc = pc.copyOf(pn * 2) }
        pk[pn] = (id(prev).toLong() shl 32) or id(w).toLong(); pc[pn] = c; pn++
        maxLog = maxOf(maxLog, ln(1.0 + c))
    }

    fun finish() {
        val order = (0 until pn).sortedBy { pk[it] }
        keys = LongArray(pn) { pk[order[it]] }; counts = IntArray(pn) { pc[order[it]] }
        totals = IntArray(vocab.size); for (i in 0 until pn) totals[(keys[i] ushr 32).toInt()] += counts[i]
        pk = LongArray(0); pc = IntArray(0); vocab.trim()
    }

    private fun lower(k: Long): Int { var lo = 0; var hi = keys.size; while (lo < hi) { val m = (lo + hi) ushr 1; if (keys[m] < k) lo = m + 1 else hi = m }; return lo }

    /** 0..1 association strength of prev -> w; uses P(w|prev) so frequent words don't dominate */
    fun score(prev: String?, w: String): Double {
        if (prev == null) return 0.0
        val a = vocab.indexOf(prev); if (a < 0) return 0.0
        val b = vocab.indexOf(w); if (b < 0) return 0.0
        val k = (a.toLong() shl 32) or b.toLong(); val i = lower(k)
        if (i >= keys.size || keys[i] != k) return 0.0
        val c = counts[i]
        return (0.5 * ln(1.0 + c) / maxLog + 0.5 * c.toDouble() / totals[a]).coerceIn(0.0, 1.0)
    }

    fun next(prev: String, n: Int): List<String> {
        val a = vocab.indexOf(prev); if (a < 0) return emptyList()
        val s = lower(a.toLong() shl 32); var e = s; while (e < keys.size && (keys[e] ushr 32).toInt() == a) e++
        return (s until e).sortedByDescending { counts[it] }.take(n).map { vocab.word((keys[it] and 0xffffffffL).toInt()) }
    }

    companion object {
        fun parse(lines: Sequence<String>) = BigramLM().apply {
            for (l in lines) { val p = l.split('\t'); if (p.size == 3) add(p[0], p[1], p[2].toIntOrNull() ?: continue) }
            finish()
        }
    }
}

data class GlideCandidate(val word: String, val lang: String, val cost: Double, val cross: Boolean)

/** learned data: word/bigram counts, forgotten words and user's gesture bias (in key widths) */
interface GlideLearning {
    fun wordBoost(word: String): Double
    fun bigramBoost(prev: String?, word: String): Double
    fun isForgotten(word: String): Boolean
    val biasX: Float
    val biasY: Float
}

class GlideDecoder(val keys: List<GKey>) {
    private val keyByChar = HashMap<Char, GKey>().apply { this@GlideDecoder.keys.forEach { put(it.ch, it) } }
    val kw: Float = keys.map { it.w }.sorted().let { if (it.isEmpty()) 1f else it[it.size / 2] }
    val kh: Float = keys.map { it.h }.sorted().let { if (it.isEmpty()) 1f else it[it.size / 2] }

    /** last trimmed (bias-corrected) path, used for learning the user's style */
    var lastStart: Pair<Float, Float>? = null; private set
    var lastEnd: Pair<Float, Float>? = null; private set

    private fun keyFor(c: Char, cross: Boolean): GKey? {
        val ch = if (c == 'ё') 'е' else c
        keyByChar[ch]?.let { return it }
        keyByChar[Fold.fold(ch)]?.let { return it }
        if (!cross) return null
        return (CrossLayout.latToCyr[ch] ?: CrossLayout.cyrToLat[ch] ?: Fold.fold(ch).let { CrossLayout.latToCyr[it] ?: CrossLayout.cyrToLat[it] })?.let { keyByChar[it] }
    }

    // key centers in key units (x / key width, y / key height): distances are isotropic in "keys"
    private val kux = FloatArray(keys.size) { keys[it].cx / kw }
    private val kuy = FloatArray(keys.size) { keys[it].cy / kh }
    private val keyIdx = HashMap<GKey, Int>().apply { this@GlideDecoder.keys.forEachIndexed { i, k -> put(k, i) } }
    private val idxCache = arrayOf(HashMap<Char, Int>(), HashMap<Char, Int>())
    /** key index for a letter, -1 = no key, -2 = joiner (hyphen / apostrophe: not swiped) */
    private fun idx(c: Char, cross: Boolean): Int = idxCache[if (cross) 1 else 0].getOrPut(c) {
        if (Fold.isJoiner(c)) -2 else keyFor(c, cross)?.let { keyIdx[it] } ?: -1
    }

    /** normalized distance of point to nearest letter key center (in key sizes) */
    private fun nearestLetterDist(x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        for (k in keys) {
            val dx = (x - k.cx) / k.w; val dy = (y - k.cy) / k.h
            val d = dx * dx + dy * dy
            if (d < best) best = d
        }
        return sqrt(best)
    }

    /** remove accidental beginnings/ends on function keys (globe, space, ?123, shift): points far from any letter key */
    fun trim(xs: FloatArray, ys: FloatArray): Pair<FloatArray, FloatArray>? {
        var s = 0; var e = xs.size - 1
        while (s <= e && nearestLetterDist(xs[s], ys[s]) > 0.72f) s++
        while (e >= s && nearestLetterDist(xs[e], ys[e]) > 0.72f) e--
        if (s > e) return null
        return xs.copyOfRange(s, e + 1) to ys.copyOfRange(s, e + 1)
    }

    /** index of the first clear direction change (> ~40 degrees), measured over about one key width */
    fun firstCorner(xs: FloatArray, ys: FloatArray): Int {
        val span = kw * 0.8f
        var j = 1
        while (j < xs.size && dist(xs[0], ys[0], xs[j], ys[j]) < span) j++
        if (j >= xs.size) return -1
        val dx0 = xs[j] - xs[0]; val dy0 = ys[j] - ys[0]; val l0 = sqrt(dx0 * dx0 + dy0 * dy0)
        var i = j
        while (i < xs.size) {
            var k = i + 1
            while (k < xs.size && dist(xs[i], ys[i], xs[k], ys[k]) < span) k++
            if (k >= xs.size) return -1
            val dx = xs[k] - xs[i]; val dy = ys[k] - ys[i]; val l = sqrt(dx * dx + dy * dy)
            val cos = (dx * dx0 + dy * dy0) / (l * l0)
            if (cos < 0.77f) return i
            i++
        }
        return -1
    }

    /** out-of-vocabulary fallback: letters under the start, every clear turn and the end of the path */
    fun rawGuess(rawX: IntArray, rawY: IntArray, n: Int): String? {
        if (n < 2 || keys.isEmpty()) return null
        val (xs, ys) = trim(FloatArray(n) { rawX[it].toFloat() }, FloatArray(n) { rawY[it].toFloat() }) ?: return null
        val (px, py) = resample(xs, ys, N)
        fun near(x: Float, y: Float) = keys.minByOrNull { dist(x, y, it.cx, it.cy) }!!.ch
        val sb = StringBuilder().append(near(px[0], py[0]))
        val step = maxOf(1, (N * kw * 0.5f / maxOf(length(xs, ys), 1f)).toInt())
        for (i in step until N - step) {
            val ax = px[i] - px[i - step]; val ay = py[i] - py[i - step]
            val bx = px[i + step] - px[i]; val by = py[i + step] - py[i]
            val la = sqrt(ax * ax + ay * ay); val lb = sqrt(bx * bx + by * by)
            if (la < 1e-3f || lb < 1e-3f) continue
            if ((ax * bx + ay * by) / (la * lb) < 0.5f) { val c = near(px[i], py[i]); if (sb.last() != c) sb.append(c) }
        }
        val e = near(px[N - 1], py[N - 1]); if (sb.last() != e || sb.length == 1) sb.append(e)
        return sb.toString().takeIf { it.length >= 2 }
    }

    // ---- 1.5.9 speed: letter-code -> key tables per lexicon, built once per decoder (layout), not per gesture ----
    private val codeMaps = arrayOf(java.util.IdentityHashMap<Lexicon, IntArray>(), java.util.IdentityHashMap<Lexicon, IntArray>())
    /** table indexed by the lexicon's letter code: key index, -1 = no key, -2 = joiner */
    private fun codeMap(lex: Lexicon, cross: Boolean): IntArray {
        val m = codeMaps[if (cross) 1 else 0]
        val old = m[lex]
        if (old != null && old.size == lex.codeCount + 1) return old
        val arr = IntArray(lex.codeCount + 1) { -1 }
        for (c in 1..lex.codeCount) arr[c] = idx(lex.charOfCode(c), cross)
        m[lex] = arr
        return arr
    }
    private val dtwA = DoubleArray(N); private val dtwB = DoubleArray(N)
    private val seqBuf = IntArray(64)
    /** diagnostics of the last decode: scan stopped at the time budget / was cancelled as stale */
    @Volatile var lastTruncated = false; private set
    @Volatile var lastAborted = false; private set
    @Volatile var lastScanned = 0; private set

    private class Elig(val a: IntArray, val n: Int, val startExtra: Double)

    /** per-gesture (per path variant) data shared by the scan passes */
    private class Ctx(
        val px: FloatArray, val py: FloatArray, val pathLen: Float, val near: FloatArray, val dwell: FloatArray,
        val endDist: FloatArray, val startDist: FloatArray, val nnTight: IntArray, val nnLoose: IntArray, val len: Int,
        val variantPenalty: Double, val qx: FloatArray, val qy: FloatArray
    )

    /** mutable state of one decode call */
    private class State(
        val max: Int, val deadline: Long, val abort: (() -> Boolean)?, val learning: GlideLearning?, val prev: String?
    ) {
        val best = HashMap<String, GlideCandidate>()
        // running list of the best costs: once it is full, hopeless words are dropped before the expensive scoring
        val topK = (max + 6).coerceAtLeast(8)
        val top = DoubleArray(topK) { Double.MAX_VALUE }
        var stop = false; var truncated = false; var aborted = false
        var scanned = 0; var scored = 0
    }

    /** ordered reachability table: nn[k * (len + 1) + j] = first path point >= j that is within [t] key units of key k, or len */
    private fun reachTable(dx: FloatArray, dy: FloatArray, t: Float): IntArray {
        val len = dx.size; val w = len + 1
        val nn = IntArray(keys.size * w)
        for (k in keys.indices) {
            val o = k * w
            nn[o + len] = len
            for (j in len - 1 downTo 0) nn[o + j] = if (dist(dx[j], dy[j], kux[k], kuy[k]) <= t) j else nn[o + j + 1]
        }
        return nn
    }

    /**
     * One scan of a lexicon, words in frequency-rank order (rounds of CHUNK words per first letter).
     * [tight] pass: start / end within [Params.radius], every letter within [Params.nearTight] of the path and in path order.
     * The loose pass (only when the tight one found nothing convincing) accepts the soft region up to radiusHard / nearMax.
     */
    private fun scan(g: Ctx, lex: Lexicon, langPenalty: Double, st: State, tight: Boolean, from: Int, to: Int) {
        val p = P
        val lsize = lex.size
        if (lsize == 0) return
        // decide whether the lexicon's script is on the keyboard; otherwise map positionally (cross)
        val cross = idx(lex.firstChar(0), false) < 0 && idx(lex.firstChar(minOf(1, lsize - 1)), false) < 0
        val keyOf = codeMap(lex, cross)
        val buf = lex.rawBuf(); val offs = lex.rawOffsets(); val bufEnd = lex.rawEnd()
        val logf = lex.logf; val maxLog = lex.maxLog
        val crossPen = if (cross) CROSS_PENALTY else 0.0
        val gateMax = if (tight) p.radius else p.radiusHard
        val nearLim = if (tight) p.nearTight else p.nearMax
        val nn = if (tight) g.nnTight else g.nnLoose
        val L = g.len; val w1 = L + 1
        val near = g.near; val dwell = g.dwell; val endDist = g.endDist
        val seq = seqBuf
        val top = st.top; val topK = st.topK
        val learning = st.learning
        val elig = ArrayList<Elig>()
        for ((c, fl) in lex.byFirst) {
            val k0 = idx(c, cross)
            if (k0 < 0) continue
            val startD = g.startDist[k0]
            if (startD > gateMax) continue
            elig.add(Elig(fl.a, fl.n, if (startD > p.radius) p.startSoftW * (startD - p.radius) else 0.0))
        }
        val pos = IntArray(elig.size)
        // lists are in frequency-rank order, so a rank window [from, to) is a contiguous part of each list
        for (ei in elig.indices) {
            val el = elig[ei]
            var lo = 0; var hi = el.n
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (el.a[mid] < from) lo = mid + 1 else hi = mid }
            pos[ei] = lo
        }
        var active = elig.size
        while (active > 0 && !st.stop) {
            active = 0
            for (ei in elig.indices) {
                val el = elig[ei]; var fi = pos[ei]
                if (fi >= el.n) continue
                val end = min(el.n, fi + CHUNK)
                val arr = el.a
                while (fi < end) {
                    val i = arr[fi++]
                    if (i >= to) { fi = el.n; break }
                    if (i >= lsize) continue
                    st.scanned++
                    val o = offs[i]
                    val e = if (i + 1 < lsize) offs[i + 1] else bufEnd
                    val len = e - o
                    if (len <= 0) continue
                    // cheap reject on the last letter before anything else
                    val lastKey = keyOf[buf[e - 1].toInt() and 0xFF]
                    if (lastKey >= 0 && endDist[lastKey] > gateMax) continue
                    // key sequence (joiners skipped, repeated letters collapse; dwell can revive a double);
                    // every letter must be near the path, and the letters must be reached in path order
                    var m = 0; var ok = true; var miss = 0.0; var doubles = 0; var at = 0
                    for (k in 0 until len) {
                        val ki = keyOf[buf[o + k].toInt() and 0xFF]
                        if (ki == -2) continue
                        if (ki < 0 || m >= seq.size) { ok = false; break }
                        if (m > 0 && seq[m - 1] == ki) { if (dwell[ki] > 0.55f) doubles++; continue }
                        val d = near[ki]
                        if (d > nearLim) { ok = false; break }
                        at = nn[ki * w1 + at]
                        if (at >= L) { ok = false; break }
                        if (d > p.nearOk) miss += d - p.nearOk
                        seq[m++] = ki
                    }
                    if (!ok || m < 2 && g.pathLen > 1.2f) continue
                    if (miss > p.missMax) continue
                    val kl = seq[m - 1]
                    val endD = endDist[kl]
                    if (endD > gateMax) continue
                    val endExtra = if (endD > p.radius) p.endSoftW * (endD - p.radius) else 0.0
                    val freq = (logf[i] / maxLog).toDouble().coerceIn(0.0, 1.0)
                    // 1.5.9: long rare words must not win over common ones: penalty grows with rarity and length
                    val rare = 1.0 - freq
                    val rarePen = p.rareW * rare * rare * (0.4 + 0.12 * max(0, m - 4))
                    val base = -p.freqW * freq + langPenalty + g.variantPenalty + crossPen + el.startExtra + endExtra -
                        doubles * p.dwellBoost + rarePen
                    val worst = top[topK - 1]
                    // learning boosts are bounded (BOOST_SLACK), so a word that cannot get into the list is dropped here
                    if (worst != Double.MAX_VALUE && base + p.missW * miss - BOOST_SLACK >= worst) continue
                    val limit = if (worst == Double.MAX_VALUE) Double.MAX_VALUE else worst - base + BOOST_SLACK
                    st.scored++
                    val geo = score(seq, m, g.px, g.py, g.pathLen, g.qx, g.qy, miss, limit, rare) ?: continue
                    val word = lex.word(i)
                    if (learning?.isForgotten(word) == true || ru.klaviatura.text.Profanity.blocks(word)) continue
                    val total = geo + base - (learning?.wordBoost(word) ?: 0.0) - (learning?.bigramBoost(st.prev, word) ?: 0.0)
                    val old = st.best[word]
                    if (old == null || old.cost > total) {
                        st.best[word] = GlideCandidate(word, lex.lang, total, cross)
                        if (total < worst) { // keep `top` sorted ascending
                            var q = topK - 1
                            while (q > 0 && top[q - 1] > total) { top[q] = top[q - 1]; q-- }
                            top[q] = total
                        }
                    }
                }
                pos[ei] = fi
                if (fi < el.n) active++
                if (System.nanoTime() > st.deadline) { st.truncated = true; st.stop = true; break }
                if (st.abort != null && st.abort.invoke()) { st.aborted = true; st.stop = true; break }
            }
        }
    }

    private fun convincing(st: State) = st.best.size >= 3 && st.top[0] <= P.looseTrigger

    @Volatile var lastTailPass = false; private set
    /** diagnostics: did the last decode need the loose (soft start/end) pass */
    @Volatile var lastLoosePass = false; private set
    @Volatile var lastScored = 0; private set

    /**
     * @param deadlineNs System.nanoTime() after which the scan stops and returns what it has found so far
     *        (words are scanned in frequency-rank order, so only rare words are lost)
     * @param abort polled between chunks; true = the gesture is stale, return nothing
     */
    @Synchronized
    fun decode(
        rawX: IntArray, rawY: IntArray, n: Int,
        lexicons: List<Pair<Lexicon, Double>>, // lexicon and language penalty (0 = current layout language)
        learning: GlideLearning? = null, prevWord: String? = null, max: Int = 18,
        deadlineNs: Long = Long.MAX_VALUE, abort: (() -> Boolean)? = null
    ): List<GlideCandidate> {
        lastTruncated = false; lastAborted = false; lastScanned = 0; lastLoosePass = false; lastTailPass = false; lastScored = 0
        if (n < 2 || keys.isEmpty()) return emptyList()
        val bx = (learning?.biasX ?: 0f) * kw; val by = (learning?.biasY ?: 0f) * kh
        val fx = FloatArray(n) { rawX[it] - bx }; val fy = FloatArray(n) { rawY[it] - by }
        val startedOffLetters = nearestLetterDist(fx[0], fy[0]) > 0.72f
        val (xs0, ys0) = trim(fx, fy) ?: return emptyList()
        // variants of the path start: if the gesture began on a function key (globe, space, shift...), the part
        // until the first clear turn is most likely just the finger travelling to the real first letter
        val variants = ArrayList<Triple<FloatArray, FloatArray, Double>>()
        variants.add(Triple(xs0, ys0, 0.0))
        if (startedOffLetters) {
            val c = firstCorner(xs0, ys0)
            if (c in 1 until xs0.size - 1) variants.add(Triple(xs0.copyOfRange(c, xs0.size), ys0.copyOfRange(c, ys0.size), 0.1))
        }
        lastStart = xs0.first() to ys0.first(); lastEnd = xs0.last() to ys0.last()
        val p = P
        val st = State(max, deadlineNs, abort, learning, prevWord)
        for ((xs, ys, variantPenalty) in variants) {
            if (st.stop) break
            // path in key units
            val ux = FloatArray(xs.size) { xs[it] / kw }; val uy = FloatArray(ys.size) { ys[it] / kh }
            val pathLen = length(ux, uy)
            val (px, py) = resample(ux, uy, N)
            val (dx, dy) = resample(ux, uy, N * 3)
            // how close the path comes to every key (for the "each letter must be passed" check)
            val near = FloatArray(keys.size) { k -> var b = Float.MAX_VALUE; for (i in dx.indices) b = min(b, dist(dx[i], dy[i], kux[k], kuy[k])); b }
            // dwell/hook: extra path length spent near a key (double letters, e.g. «анна», off, success)
            val dwell = FloatArray(keys.size)
            if (dx.size >= 2) {
                for (i in 1 until dx.size) {
                    val step = dist(dx[i - 1], dy[i - 1], dx[i], dy[i])
                    if (step <= 0f) continue
                    for (k in keys.indices) {
                        val d0 = dist(dx[i - 1], dy[i - 1], kux[k], kuy[k])
                        val d1 = dist(dx[i], dy[i], kux[k], kuy[k])
                        if (min(d0, d1) < 0.45f) dwell[k] += step
                    }
                }
                for (k in dwell.indices) dwell[k] = max(0f, dwell[k] - 0.55f)
            }
            val sx = px[0]; val sy = py[0]; val ex = px[N - 1]; val ey = py[N - 1]
            val g = Ctx(
                px, py, pathLen, near, dwell,
                FloatArray(keys.size) { dist(ex, ey, kux[it], kuy[it]) }, FloatArray(keys.size) { dist(sx, sy, kux[it], kuy[it]) },
                reachTable(dx, dy, p.nearTight), reachTable(dx, dy, p.nearMax), dx.size,
                variantPenalty, FloatArray(N), FloatArray(N)
            )
            // stage 1: tight gate, frequent words only (the file is in frequency-rank order, so rank = index)
            val head = p.headWords
            for ((lex, langPenalty) in lexicons) {
                if (st.stop) break
                synchronized(lex) { scan(g, lex, langPenalty, st, true, 0, head) }
            }
            // stage 2: the rare tail, only when stage 1 found nothing convincing
            if (!st.stop && !convincing(st)) {
                lastTailPass = true
                for ((lex, langPenalty) in lexicons) {
                    if (st.stop) break
                    synchronized(lex) { scan(g, lex, langPenalty, st, true, head, Int.MAX_VALUE) }
                }
            }
            // stage 3: loose gate (soft start / end), only when still nothing convincing
            if (!st.stop && !convincing(st)) {
                lastLoosePass = true
                for ((lex, langPenalty) in lexicons) {
                    if (st.stop) break
                    synchronized(lex) { scan(g, lex, langPenalty, st, false, 0, Int.MAX_VALUE) }
                }
            }
        }
        lastScanned = st.scanned; lastScored = st.scored
        lastTruncated = st.truncated; lastAborted = st.aborted
        if (st.aborted) return emptyList()
        val out = ArrayList(st.best.values)
        out.sortBy { it.cost }
        val seen = HashSet<String>()
        return out.filter { seen.add(it.word) }.take(max)
    }

    /**
     * geometric cost of a word's key sequence against the path (all in key units):
     * shape (uniformly resampled point distance), ordered key alignment (each key in order must be hit by some part of
     * the path, DTW-like), deviation of the path from the word's polyline, start / end distance, missed keys, length.
     */
    private fun score(seq: IntArray, m: Int, px: FloatArray, py: FloatArray, pathLen: Float, qx: FloatArray, qy: FloatArray, miss: Double,
                      limit: Double = Double.MAX_VALUE, rare: Double = 0.0): Double? {
        val p = P
        var tLen = 0f
        for (j in 1 until m) tLen += dist(kux[seq[j - 1]], kuy[seq[j - 1]], kux[seq[j]], kuy[seq[j]])
        if (pathLen < tLen * 0.38f - 1f) return null
        if (pathLen > tLen * 2.7f + 2.5f) return null
        // the template starts / ends exactly on the first / last key: these cheap terms are checked before anything else
        val start = dist(px[0], py[0], kux[seq[0]], kuy[seq[0]]).toDouble()
        val end = dist(px[N - 1], py[N - 1], kux[seq[m - 1]], kuy[seq[m - 1]]).toDouble()
        val lenPen = abs(ln(((pathLen + 1f) / (tLen + 1f)).toDouble()))
        val lenTerm = p.lenW * lenPen * (1.0 + p.rareLenW * rare)
        // every remaining term is >= 0: stop early when this already cannot make the list
        var partial = p.startW * start + p.endW * end + p.missW * miss + lenTerm
        if (partial > limit) return null
        // template resampled to N points
        templateInto(seq, m, tLen, qx, qy)
        var loc = 0.0
        for (i in 0 until N) loc += dist(px[i], py[i], qx[i], qy[i])
        loc /= N
        if (loc > p.locMax) return null
        partial += p.locW * loc
        if (partial > limit) return null
        // ordered alignment: key j is matched to path point a_j, a non-decreasing, a_1 near the start, a_m near the end
        var prev = dtwA; var cur = dtwB
        for (i in 0 until N) { val d = dist(px[i], py[i], kux[seq[0]], kuy[seq[0]]); prev[i] = d * d + i * p.skipW }
        for (j in 1 until m) {
            val kx = kux[seq[j]]; val ky = kuy[seq[j]]
            var run = Double.MAX_VALUE
            for (i in 0 until N) {
                run = min(run, prev[i])
                val d = dist(px[i], py[i], kx, ky)
                cur[i] = run + d * d
            }
            val t = prev; prev = cur; cur = t
        }
        var align = Double.MAX_VALUE
        for (i in 0 until N) align = min(align, prev[i] + (N - 1 - i) * p.skipW)
        align /= m
        partial += p.alignW * align
        if (partial > limit) return null
        // path points far from the word's polyline (e.g. an extra excursion to another key)
        var dev = 0.0
        for (i in 0 until N) {
            var b = Float.MAX_VALUE
            for (j in 1 until m) b = min(b, segDist(px[i], py[i], kux[seq[j - 1]], kuy[seq[j - 1]], kux[seq[j]], kuy[seq[j]]))
            if (m == 1) b = dist(px[i], py[i], kux[seq[0]], kuy[seq[0]])
            if (b > p.devOk) dev += (b - p.devOk) * (b - p.devOk)
        }
        dev /= N
        partial += p.devW * dev
        if (partial > limit) return null
        // shape channel (translation-invariant): compare centroid-centered paths
        var pcx = 0f; var pcy = 0f; var qcx = 0f; var qcy = 0f
        for (i in 0 until N) { pcx += px[i]; pcy += py[i]; qcx += qx[i]; qcy += qy[i] }
        val inv = 1f / N
        pcx *= inv; pcy *= inv; qcx *= inv; qcy *= inv
        var shape = 0.0
        for (i in 0 until N) shape += dist(px[i] - pcx, py[i] - pcy, qx[i] - qcx, qy[i] - qcy)
        shape /= N
        return partial + p.shapeW * shape
    }

    private fun templateInto(seq: IntArray, m: Int, tLen: Float, ox: FloatArray, oy: FloatArray) {
        if (m == 1 || tLen == 0f) { ox.fill(kux[seq[0]]); oy.fill(kuy[seq[0]]); return }
        val step = tLen / (N - 1)
        var seg = 1; var segStart = 0f
        var segLen = dist(kux[seq[0]], kuy[seq[0]], kux[seq[1]], kuy[seq[1]])
        for (i in 0 until N) {
            val t = i * step
            while (seg < m - 1 && t > segStart + segLen) { segStart += segLen; seg++; segLen = dist(kux[seq[seg - 1]], kuy[seq[seg - 1]], kux[seq[seg]], kuy[seq[seg]]) }
            val f = if (segLen > 0f) ((t - segStart) / segLen).coerceIn(0f, 1f) else 0f
            val ax = kux[seq[seg - 1]]; val ay = kuy[seq[seg - 1]]
            ox[i] = ax + (kux[seq[seg]] - ax) * f; oy[i] = ay + (kuy[seq[seg]] - ay) * f
        }
    }

    /** tunable weights (tuned on the synthetic benchmark, see GlideBenchTest / tools) */
    class Params {
        /** tight start/end gate (key widths); words slightly outside still compete with a penalty */
        @JvmField var radius = 1.65f
        /** hard start/end cutoff — diagonal neighbours plus finger jitter */
        @JvmField var radiusHard = 2.35f
        @JvmField var nearOk = 1.0f
        @JvmField var nearMax = 2.05f
        /** tight pass: letters must pass within this distance of the path (key units) */
        @JvmField var nearTight = 1.45f
        /** stage 1 scans only the most frequent words (by rank); the rare tail only when nothing convincing was found */
        @JvmField var headWords = 150_000
        /** the loose pass runs only when the best tight cost is worse than this */
        @JvmField var looseTrigger = 2.2
        @JvmField var missMax = 1.8
        @JvmField var locMax = 1.85
        @JvmField var skipW = 0.02
        @JvmField var devOk = 0.15f
        @JvmField var locW = 1.85
        @JvmField var alignW = 0.58
        @JvmField var devW = 3.6
        @JvmField var startW = 0.8
        @JvmField var endW = 1.85
        @JvmField var missW = 1.1
        @JvmField var lenW = 0.35
        @JvmField var freqW = 2.5
        /** SHARK2 shape channel: path and template compared after subtracting centroids */
        @JvmField var shapeW = 1.2
        @JvmField var startSoftW = 0.85
        @JvmField var endSoftW = 1.05
        @JvmField var dwellBoost = 0.55
        /** 1.5.9: penalty for rare (and long) words, and extra weight of the path-length mismatch for them */
        @JvmField var rareW = 1.0
        @JvmField var rareLenW = 1.5
    }

    companion object {
        const val N = 40
        /** words scanned per first-letter list before the time budget is checked */
        const val CHUNK = 1024
        /** upper bound of learned boosts (word / bigram / LM) used for exact pruning */
        const val BOOST_SLACK = 4.0
        @JvmField var P = Params()
        /** weight of base context LM: modest, geometry dominates */
        const val CTX_W = 0.6
        const val CROSS_PENALTY = 0.45

        fun segDist(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val vx = bx - ax; val vy = by - ay; val l2 = vx * vx + vy * vy
            val t = if (l2 == 0f) 0f else (((x - ax) * vx + (y - ay) * vy) / l2).coerceIn(0f, 1f)
            return dist(x, y, ax + t * vx, ay + t * vy)
        }
        fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float { val dx = ax - bx; val dy = ay - by; return sqrt(dx * dx + dy * dy) }
        fun length(x: FloatArray, y: FloatArray): Float { var l = 0f; for (i in 1 until x.size) l += dist(x[i - 1], y[i - 1], x[i], y[i]); return l }

        fun resample(x: FloatArray, y: FloatArray, n: Int): Pair<FloatArray, FloatArray> {
            val ox = FloatArray(n); val oy = FloatArray(n)
            val total = length(x, y)
            if (x.size == 1 || total == 0f) { ox.fill(x[0]); oy.fill(y[0]); return ox to oy }
            val step = total / (n - 1)
            ox[0] = x[0]; oy[0] = y[0]
            var idx = 1; var acc = 0f
            var cx = x[0]; var cy = y[0]; var i = 1
            while (i < x.size && idx < n) {
                val d = dist(cx, cy, x[i], y[i])
                if (acc + d >= step && d > 0f) {
                    val t = (step - acc) / d
                    val nx = cx + t * (x[i] - cx); val ny = cy + t * (y[i] - cy)
                    ox[idx] = nx; oy[idx] = ny; idx++
                    cx = nx; cy = ny; acc = 0f
                } else { acc += d; cx = x[i]; cy = y[i]; i++ }
            }
            while (idx < n) { ox[idx] = x.last(); oy[idx] = y.last(); idx++ }
            return ox to oy
        }
    }
}
