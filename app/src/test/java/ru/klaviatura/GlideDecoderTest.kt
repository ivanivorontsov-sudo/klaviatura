package ru.klaviatura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import ru.klaviatura.glide.GKey
import ru.klaviatura.glide.GlideDecoder
import ru.klaviatura.glide.Lexicon
import ru.klaviatura.glide.CrossLayout
import java.io.File
import kotlin.math.sin
import org.junit.Test

class GlideDecoderTest {
    companion object {
        const val W = 100f; const val H = 150f
        fun rows(rows: List<String>, offsets: List<Float>) = rows.flatMapIndexed { r, s ->
            s.mapIndexed { i, c -> GKey(c, offsets[r] + W / 2 + W * i, H / 2 + H * r, W, H) }
        }
        val jcuken = rows(listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю"), listOf(0f, 0f, 150f))
        val qwerty = rows(listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"), listOf(0f, 50f, 150f))
        val SPACE = 550f to 525f
        val GLOBE = 200f to 525f
        val SHIFT = 75f to 375f

        private fun findAsset(name: String): File = listOf("src/main/assets/glide/$name", "app/src/main/assets/glide/$name")
            .map { File(it) }.first { it.exists() }
        val ru by lazy { Lexicon.parse("ru", findAsset("ru.tsv").readLines().asSequence()) }
        val en by lazy { Lexicon.parse("en", findAsset("en.tsv").readLines().asSequence()) }

        /** simulated finger path through the given points, with a little wobble */
        fun path(points: List<Pair<Float, Float>>): Pair<IntArray, IntArray> {
            val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
            var t = 0
            for (i in 1 until points.size) {
                val (ax, ay) = points[i - 1]; val (bx, by) = points[i]
                val steps = 12
                for (s in 0 until steps) {
                    val f = s / steps.toFloat()
                    xs.add((ax + (bx - ax) * f + 9 * sin(t * 0.7)).toInt()); ys.add((ay + (by - ay) * f + 7 * sin(t * 1.3)).toInt()); t++
                }
            }
            xs.add(points.last().first.toInt()); ys.add(points.last().second.toInt())
            return xs.toIntArray() to ys.toIntArray()
        }

        fun wordPoints(keys: List<GKey>, word: String, map: (Char) -> Char = { it }) =
            word.map { c -> keys.first { it.ch == map(c) }.let { it.cx to it.cy } }
    }

    private fun decode(keys: List<GKey>, pts: List<Pair<Float, Float>>, lex: List<Pair<Lexicon, Double>>): List<String> {
        val (x, y) = path(pts)
        return GlideDecoder(keys).decode(x, y, x.size, lex).map { it.word }
    }

    private fun assertTop(expected: String, result: List<String>, top: Int = 1) =
        assertTrue("expected '$expected' in top $top, got ${result.take(5)}", result.take(top).contains(expected))

    @Test fun nashiCase_startOnGlobeAndSpace_isIgnored() {
        // Ivan's real case: gesture starts on globe, brushes space and goes up to ш -> must not give «наши»
        val pts = listOf(GLOBE, SPACE) + wordPoints(jcuken, "школа")
        val r = decode(jcuken, pts, listOf(ru to 0.0, en to 0.3))
        assertTop("школа", r)
        assertNotEquals("наши", r.first())
    }

    @Test fun nashiCase_shagi() {
        val r = decode(jcuken, listOf(SPACE) + wordPoints(jcuken, "шаги"), listOf(ru to 0.0, en to 0.3))
        assertTop("шаги", r)
    }

    @Test fun nashiCase_realNashiStillWorks() {
        val r = decode(jcuken, wordPoints(jcuken, "наши"), listOf(ru to 0.0, en to 0.3))
        assertTop("наши", r)
    }

    @Test fun startOnShiftIgnored() {
        val r = decode(jcuken, listOf(SHIFT) + wordPoints(jcuken, "сегодня"), listOf(ru to 0.0))
        assertTop("сегодня", r)
    }

    @Test fun typicalRussianWords() {
        for (w in listOf("привет", "спасибо", "хорошо", "сегодня", "работа", "когда", "только", "может", "человек", "дома"))
            assertTop(w, decode(jcuken, wordPoints(jcuken, w), listOf(ru to 0.0, en to 0.3)), 2)
    }

    @Test fun typicalEnglishWords() {
        for (w in listOf("hello", "world", "thanks", "keyboard", "people", "because", "about", "would"))
            assertTop(w, decode(qwerty, wordPoints(qwerty, w), listOf(en to 0.0, ru to 0.3)), 2)
    }

    @Test fun englishOfferedOnRussianLayout() {
        val lat2cyr = CrossLayout.latToCyr
        val r = decode(jcuken, wordPoints(jcuken, "hello") { lat2cyr[it]!! }, listOf(ru to 0.0, en to 0.3))
        assertTop("hello", r, 5)
    }

    @Test fun ruWordsWithYo() {
        assertTop("ещё".replace('ё', 'е').let { if (ru.contains("ещё")) "ещё" else it },
            decode(jcuken, wordPoints(jcuken, "еще"), listOf(ru to 0.0)), 3)
    }

    @Test fun trimmingAllNonLetterGivesNothing() {
        val (x, y) = path(listOf(GLOBE, SPACE))
        assertEquals(0, GlideDecoder(jcuken).decode(x, y, x.size, listOf(ru to 0.0)).size)
    }
}
