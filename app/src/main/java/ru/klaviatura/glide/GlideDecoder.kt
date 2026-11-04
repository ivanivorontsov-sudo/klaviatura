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

class Lexicon(val lang: String) {
    val words = ArrayList<String>()
    val logf = ArrayList<Float>()
    private val index = HashMap<String, Int>()
    val byFirst = HashMap<Char, MutableList<Int>>()
    var maxLog = 1f

    fun contains(w: String) = index.containsKey(w)

    fun add(word: String, count: Long) {
        if (word.isEmpty() || index.containsKey(word)) return
        val i = words.size
        words.add(word); val l = ln(1.0 + count).toFloat(); logf.add(l); maxLog = max(maxLog, l)
        index[word] = i
        byFirst.getOrPut(word[0]) { ArrayList() }.add(i)
    }

    companion object {
        fun parse(lang: String, lines: Sequence<String>): Lexicon {
            val lex = Lexicon(lang)
            for (line in lines) {
                val t = line.indexOf('\t'); if (t <= 0) continue
                lex.add(line.substring(0, t), line.substring(t + 1).trim().toLongOrNull() ?: 1L)
            }
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
            val sample = lex.words.firstOrNull() ?: continue
            val cross = keyFor(sample.first(), false) == null
            val startChars = HashSet<Char>()
            for (c in lex.byFirst.keys) {
                val k = keyFor(c, cross) ?: continue
                if (dist(sx, sy, k.cx, k.cy) / kw < radius) startChars.add(c)
            }
            for (c in startChars) {
                for (i in lex.byFirst[c]!!) {
                    val w = lex.words[i]
                    val lastK = keyFor(w.last(), cross) ?: continue
                    if (dist(ex, ey, lastK.cx, lastK.cy) / kw > radius) continue
                    if (learning?.isForgotten(w) == true) continue
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
