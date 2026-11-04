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

    class IntList { var a = IntArray(16); var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v } }

    /** word list facade (strings are created on access) */
    val words: List<String> = object : AbstractList<String>() {
        override val size get() = this@Lexicon.size
        override fun get(index: Int) = word(index)
    }
    val logf: FloatArray get() = lf
    val byFirst: Map<Char, IntList> get() = firstLists

    fun word(i: Int): String { val o = off[i]; val l = len(i); return String(CharArray(l) { dec(buf[o + it]) }) }
    fun firstChar(i: Int) = dec(buf[off[i]])
    private fun len(i: Int) = (if (i + 1 < size) off[i + 1] else bufLen) - off[i]

    private fun hash(w: CharSequence): Int { var h = 0; for (c in w) h = h * 31 + c.code; return h and 0x7fffffff }
    private fun eq(i: Int, w: CharSequence): Boolean {
        val l = len(i); if (l != w.length) return false
        val o = off[i]; for (k in 0 until l) if (buf[o + k] != enc(w[k])) return false; return true
    }
    private fun find(w: CharSequence): Int {
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

    fun add(word: String, count: Long) {
        if (word.isEmpty() || word.any { enc(it) == BAD } || find(word) >= 0) return
        val i = size
        if (i + 1 >= off.size) { off = off.copyOf(off.size * 2); lf = lf.copyOf(lf.size * 2) }
        while (bufLen + word.length > buf.size) buf = buf.copyOf(buf.size * 2)
        for (k in word.indices) buf[bufLen + k] = enc(word[k]); off[i] = bufLen; bufLen += word.length
        val l = ln(1.0 + count).toFloat(); lf[i] = l; maxLog = max(maxLog, l)
        size++
        if (size * 2 > table.size) grow() else put(i, word)
        firstLists.getOrPut(word[0]) { IntList() }.add(i)
    }

    /** release slack after bulk loading */
    fun trim() { buf = buf.copyOf(bufLen); off = off.copyOf(size + 1); lf = lf.copyOf(size + 1)
        firstLists.values.forEach { it.a = it.a.copyOf(it.n) } }

    companion object {
        /** 1 byte per letter (cp1251-like): halves the memory of the 1.36M-form Russian lexicon */
        const val BAD: Byte = 0x7F
        fun enc(c: Char): Byte = when {
            c.code < 0x7F -> c.code.toByte()
            c in 'а'..'я' -> (0xE0 + (c - 'а')).toByte()
            c in 'А'..'Я' -> (0xC0 + (c - 'А')).toByte()
            c == 'ё' -> 0xB8.toByte(); c == 'Ё' -> 0xA8.toByte()
            else -> BAD
        }
        fun dec(b: Byte): Char { val v = b.toInt() and 0xFF; return when {
            v < 0x7F -> v.toChar(); v >= 0xE0 -> 'а' + (v - 0xE0); v >= 0xC0 -> 'А' + (v - 0xC0)
            v == 0xB8 -> 'ё'; v == 0xA8 -> 'Ё'; else -> '?' } }
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
        if (!cross) return null
        return (CrossLayout.latToCyr[ch] ?: CrossLayout.cyrToLat[ch])?.let { keyByChar[it] }
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

    fun decode(
        rawX: IntArray, rawY: IntArray, n: Int,
        lexicons: List<Pair<Lexicon, Double>>, // lexicon and language penalty (0 = current layout language)
        learning: GlideLearning? = null, prevWord: String? = null, max: Int = 18
    ): List<GlideCandidate> {
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
        val best = HashMap<String, GlideCandidate>()
        for ((xs, ys, variantPenalty) in variants) {
        val pathLen = length(xs, ys)
        val (px, py) = resample(xs, ys, N)
        val sx = xs.first(); val sy = ys.first(); val ex = xs.last(); val ey = ys.last()
        val radius = 1.6f
        for ((lex, langPenalty) in lexicons) {
            // decide whether the lexicon's script is on the keyboard; otherwise map positionally (cross)
            if (lex.size == 0) continue
            val sample = lex.word(0)
            val cross = keyFor(sample.first(), false) == null
            val startChars = HashSet<Char>()
            for (c in lex.byFirst.keys) {
                val k = keyFor(c, cross) ?: continue
                if (dist(sx, sy, k.cx, k.cy) / kw < radius) startChars.add(c)
            }
            for (c in startChars) {
                val fl = lex.byFirst[c]!!
                for (fi in 0 until fl.n) { val i = fl.a[fi]
                    val w = lex.word(i)
                    val lastK = keyFor(w.last(), cross) ?: continue
                    if (dist(ex, ey, lastK.cx, lastK.cy) / kw > radius) continue
                    if (learning?.isForgotten(w) == true || ru.klaviatura.text.Profanity.blocks(w)) continue
                    val cost = score(w, cross, px, py, pathLen, xs, ys) ?: continue
                    val freq = lex.logf[i] / lex.maxLog
                    val total = cost - FREQ_W * freq - (learning?.wordBoost(w) ?: 0.0) -
                        (learning?.bigramBoost(prevWord, w) ?: 0.0) + langPenalty + variantPenalty + (if (cross) CROSS_PENALTY else 0.0)
                    val old = best[w]
                    if (old == null || old.cost > total) best[w] = GlideCandidate(w, lex.lang, total, cross)
                }
            }
        }
        }
        val out = ArrayList(best.values)
        out.sortBy { it.cost }
        val seen = HashSet<String>()
        return out.filter { seen.add(it.word) }.take(max)
    }

    private fun score(w: String, cross: Boolean, px: FloatArray, py: FloatArray, pathLen: Float, xs: FloatArray, ys: FloatArray): Double? {
        val tx = ArrayList<Float>(); val ty = ArrayList<Float>()
        var lastK: GKey? = null
        for (c in w) {
            val k = keyFor(c, cross) ?: return null
            if (k !== lastK) { tx.add(k.cx); ty.add(k.cy) }
            lastK = k
        }
        val txa = tx.toFloatArray(); val tya = ty.toFloatArray()
        val tLen = length(txa, tya)
        // quick length check: path much shorter than template is impossible
        if (pathLen < tLen * 0.45f - kw) return null
        val (qx, qy) = resample(txa, tya, N)
        var loc = 0.0
        for (i in 0 until N) loc += dist(px[i], py[i], qx[i], qy[i])
        loc /= N * kw
        if (loc > 1.6) return null
        val start = dist(px[0], py[0], qx[0], qy[0]) / kw
        val end = dist(px[N - 1], py[N - 1], qx[N - 1], qy[N - 1]) / kw
        // every key of the word must be passed by the path
        var miss = 0.0
        for (j in txa.indices) {
            var best = Float.MAX_VALUE
            var i = 0
            while (i < xs.size) { best = min(best, dist(xs[i], ys[i], txa[j], tya[j])); i++ }
            val d = best / kw
            if (d > 0.55) miss += (d - 0.55)
        }
        val ratio = (pathLen + kw) / (tLen + kw)
        val lenPen = abs(ln(ratio.toDouble()))
        return LOC_W * loc + START_W * start + END_W * end + MISS_W * miss + LEN_W * lenPen
    }

    companion object {
        const val N = 40
        const val LOC_W = 2.2
        const val START_W = 1.1
        const val END_W = 1.3
        const val MISS_W = 2.5
        const val LEN_W = 0.6
        const val FREQ_W = 1.1
        /** weight of base context LM: modest, geometry dominates */
        const val CTX_W = 0.6
        const val CROSS_PENALTY = 0.45

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
