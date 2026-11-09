package ru.klaviatura

import org.junit.Assert.assertTrue
import org.junit.Test
import ru.klaviatura.glide.GKey
import ru.klaviatura.glide.GlideDecoder
import ru.klaviatura.glide.Lexicon
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 1.4.0 realistic benchmark: real phone geometry (ЙЦУКЕН with 11 keys per row, QWERTY 10/9/7),
 * gaussian aiming error per key, a per-gesture offset, corner cutting (Chaikin smoothing) and finger tremor.
 * Sets: frequent words, short words (2–4 letters), hyphenated words, rare inflections, IT words.
 * Run with -Dkl.bench=1 to only report (no thresholds).
 */
class GlideBenchTest {
    companion object {
        const val W = 98f; const val H = 150f
        private fun rows(rows: List<String>, offsets: List<Float>, w: Float) = rows.flatMapIndexed { r, s ->
            s.mapIndexed { i, c -> GKey(c, offsets[r] + w / 2 + w * i, H / 2 + H * r, w, H) }
        }
        val ru11 = rows(listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю"), listOf(0f, 0f, W), W)
        const val WE = 108f
        val qwerty = rows(listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"), listOf(0f, WE / 2, WE * 1.5f), WE)
        val qwertyPl = qwerty
        val uk11 = rows(listOf("йцукенгшщзхї", "фівапролджє", "ячсмитьбю"), listOf(0f, 0f, W), W * 11 / 12)

        val HYPH_RU = listOf("где-то", "как-то", "почему-то", "кое-что", "из-за", "по-моему", "что-то", "кто-то", "когда-то",
            "куда-то", "какой-то", "какая-то", "что-нибудь", "кто-нибудь", "из-под", "по-другому", "по-прежнему", "всё-таки",
            "по-русски", "чуть-чуть", "кое-как", "зачем-то", "откуда-то", "чей-то", "так-то", "вот-вот", "по-настоящему", "какие-то")
        val HYPH_EN = listOf("well-known", "e-mail", "self-made", "long-term", "follow-up", "built-in", "up-to-date", "real-time")
        val IT_EN = listOf("const", "async", "await", "lambda", "struct", "boolean", "kotlin", "typescript", "kubernetes", "docker",
            "github", "localhost", "middleware", "endpoint", "refactor", "println", "namespace", "iterator", "nullptr", "webhook")
        val IT_RU = listOf("закоммитил", "задеплоить", "запушила", "рефакторинг", "фронтенд", "бэкенд", "репозиторий", "сервер", "базы", "функция")

        private fun file(name: String) = listOf("src/main/assets/glide/$name", "app/src/main/assets/glide/$name").map { File(it) }.first { it.exists() }
        fun lex(lang: String) = Lexicon.parse(lang, file("$lang.tsv").readLines().asSequence())
        fun top(lang: String, n: Int) = file("$lang.tsv").useLines { s -> s.map { it.substringBefore('\t') }
            .filter { w -> w.length >= 2 && w.all { it.isLetter() } && w == w.lowercase() }.take(n).toList() }

        private fun gauss(r: Random): Float { var s = 0f; repeat(6) { s += r.nextFloat() }; return (s - 3f) / 0.7071f }

        fun gesture(keys: List<GKey>, word: String, rnd: Random, sigma: Float = 0.21f): Pair<IntArray, IntArray> {
            val byCh = keys.associateBy { it.ch }
            val seq = ArrayList<GKey>()
            for (c0 in word) {
                val c = when (c0) { 'ё' -> 'е'; 'ъ' -> 'ь'; else -> c0 }
                val k = byCh[c] ?: byCh[ru.klaviatura.glide.Fold.fold(c)] ?: continue
                if (seq.lastOrNull() !== k) seq.add(k)
            }
            val ox = gauss(rnd) * 0.08f * W; val oy = gauss(rnd) * 0.08f * H
            var px = seq.map { it.cx + ox + gauss(rnd) * sigma * it.w }.toMutableList()
            var py = seq.map { it.cy + oy + gauss(rnd) * sigma * 0.8f * it.h }.toMutableList()
            if (px.size == 1) { px.add(px[0] + 6f); py.add(py[0] + 4f) }
            repeat(1) { // corner cutting (one Chaikin-like pass, 20 %), endpoints kept
                val nx = mutableListOf(px.first()); val ny = mutableListOf(py.first())
                for (i in 0 until px.size - 1) {
                    nx.add(0.8f * px[i] + 0.2f * px[i + 1]); ny.add(0.8f * py[i] + 0.2f * py[i + 1])
                    nx.add(0.2f * px[i] + 0.8f * px[i + 1]); ny.add(0.2f * py[i] + 0.8f * py[i + 1])
                }
                nx.add(px.last()); ny.add(py.last()); px = nx; py = ny
            }
            val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
            var t = 0
            for (i in 0 until px.size - 1) {
                val d = sqrt((px[i + 1] - px[i]) * (px[i + 1] - px[i]) + (py[i + 1] - py[i]) * (py[i + 1] - py[i]))
                val steps = maxOf(1, (d / (W * (0.10f + 0.08f * rnd.nextFloat()))).toInt())
                for (s in 0 until steps) {
                    val f = s / steps.toFloat()
                    xs.add((px[i] + (px[i + 1] - px[i]) * f + 2.5f * gauss(rnd)).toInt()); ys.add((py[i] + (py[i + 1] - py[i]) * f + 2.5f * gauss(rnd)).toInt()); t++
                }
            }
            xs.add(px.last().toInt()); ys.add(py.last().toInt())
            return xs.toIntArray() to ys.toIntArray()
        }

        data class Res(val tag: String, val n: Int, val t1: Int, val t3: Int, val misses: List<String>) {
            val top1 get() = 100.0 * t1 / maxOf(1, n); val top3 get() = 100.0 * t3 / maxOf(1, n)
            override fun toString() = "[bench $tag] top1=${"%.1f".format(top1)}% top3=${"%.1f".format(top3)}% (n=$n)"
        }

        fun run(tag: String, keys: List<GKey>, words: List<String>, lexs: List<Pair<Lexicon, Double>>, seeds: Int = 2, prev: (String) -> String? = { null }): Res {
            val d = GlideDecoder(keys)
            var t1 = 0; var t3 = 0; var n = 0; val miss = ArrayList<String>()
            for (w in words) for (seed in 0 until seeds) {
                val (x, y) = gesture(keys, w, Random(w.hashCode() * 131 + seed))
                val r = d.decode(x, y, x.size, lexs, null, prev(w)).map { it.word.replace('ё', 'е') }
                val ww = w.replace('ё', 'е')
                n++; if (r.firstOrNull() == ww) t1++
                if (r.take(3).contains(ww)) t3++
                if (r.firstOrNull() != ww && miss.size < 60) miss.add("$w→${r.take(3)}")
            }
            return Res(tag, n, t1, t3, miss)
        }
    }

    @Test fun realisticBenchmark() {
        val ru = lex("ru"); val en = lex("en")
        val rnd = Random(140)
        val ruTop = top("ru", 3000); val enTop = top("en", 3000)
        val ruFreq = (ruTop.take(150) + List(250) { ruTop[150 + rnd.nextInt(ruTop.size - 150)] }).distinct()
        val ruShort = ruTop.filter { it.length in 2..4 }.take(160)
        val enFreq = (enTop.take(100) + List(150) { enTop[100 + rnd.nextInt(enTop.size - 100)] }).distinct()
        val enShort = enTop.filter { it.length in 2..4 }.take(100)
        val ruL = listOf(ru to 0.0, en to 0.3); val enL = listOf(en to 0.0, ru to 0.3)
        val results = listOf(
            run("RU frequent", ru11, ruFreq, ruL),
            run("RU short", ru11, ruShort, ruL),
            run("RU hyphen", ru11, HYPH_RU, ruL),
            run("RU corpus", ru11, GlideCorpusTest.RU, ruL),
            run("RU IT", ru11, IT_RU, ruL),
            run("EN frequent", qwerty, enFreq, enL),
            run("EN short", qwerty, enShort, enL),
            run("EN hyphen", qwerty, HYPH_EN, enL),
            run("EN IT", qwerty, IT_EN, enL),
        )
        var n = 0; var t1 = 0; var t3 = 0
        for (r in results) { println(r); println("  misses: " + r.misses.take(40).joinToString("; ")); n += r.n; t1 += r.t1; t3 += r.t3 }
        println("[bench TOTAL] top1=${"%.1f".format(100.0 * t1 / n)}% top3=${"%.1f".format(100.0 * t3 / n)}% (n=$n)")
        File(System.getProperty("java.io.tmpdir"), "kl_bench.txt").writeText(results.joinToString("\n") + "\nTOTAL top1=${"%.1f".format(100.0 * t1 / n)} top3=${"%.1f".format(100.0 * t3 / n)} n=$n\n" +
            results.joinToString("\n") { it.tag + ": " + it.misses.joinToString("; ") })
        assertTrue(100.0 * t1 / n >= 85.0)
    }
}
