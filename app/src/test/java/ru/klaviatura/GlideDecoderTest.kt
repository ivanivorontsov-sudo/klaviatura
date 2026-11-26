package ru.klaviatura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import ru.klaviatura.glide.GKey
import ru.klaviatura.glide.GlideDecoder
import ru.klaviatura.glide.GlideEngine
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
        val jcuken = rows(listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"), listOf(0f, 0f, 150f))
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

    @Test fun curatedRussianWordsCanBeAddedAndBoosted() {
        val lex = Lexicon("ru")
        listOf("спиртовой", "спиртовая", "салфетка", "салфеткой").forEach { lex.addOrBoost(it, 8_000L) }
        assertTrue(lex.contains("спиртовой"))
        assertTrue(lex.contains("спиртовая"))
        assertTrue(lex.contains("салфетка"))
        assertTrue(lex.contains("салфеткой"))
        val index = lex.indexOf("спиртовой")
        val beforeBoost = lex.logf[index]
        lex.addOrBoost("спиртовой", 100_000L)
        assertTrue(lex.logf[index] > beforeBoost)
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

    @Test fun latinBrandWordsAreSwipeableOnRussianLayout() {
        val brands = Lexicon.parse("en", findAsset("en_extra.tsv").readLines().asSequence())
        val lexicons = listOf(ru to 0.0, brands to 0.3)
        for (word in listOf("realme", "samsung", "xiaomi", "huawei", "oneplus")) {
            assertTrue("brand lexicon missing $word", brands.contains(word))
            val points = wordPoints(jcuken, word) { CrossLayout.latToCyr[it]!! }
            assertTop(word, decode(jcuken, points, lexicons).map { it.lowercase() }, 3)
        }
    }

    @Test fun builtInRussianSlangDictionaryContainsSwipeableTerms() {
        val slang = Lexicon.parse("ru", findAsset("ru_slang.tsv").readLines().asSequence())
        for (word in listOf("вайб", "кринж", "имба", "зашквар", "чилить")) {
            assertTrue("built-in slang dictionary missing $word", slang.contains(word))
            assertTop(word, decode(jcuken, wordPoints(jcuken, word), listOf(slang to 0.0)), 3)
        }
    }

    @Test fun longRussianInflectionsAreInBuiltInPriorityLexicon() {
        val priority = Lexicon.parse("ru_priority", findAsset("ru_priority.tsv").readLines().asSequence())
        for (word in listOf("перспективно", "лимитов", "перспективный", "лимитами")) {
            assertTrue("priority lexicon missing $word", priority.contains(word))
            assertTop(word, decode(jcuken, wordPoints(jcuken, word), listOf(priority to 0.0)), 2)
        }
    }

    @Test fun longRareWordsSurviveARealisticNoisySwipe() {
        val priority = Lexicon.parse("ru_priority", findAsset("ru_priority.tsv").readLines().asSequence())
        val targets = listOf(
            "перспективно", "достопримечательность", "непредсказуемость",
            "производительностью", "электротехнический", "распознавание"
        )
        for (word in targets) {
            assertTrue("priority lexicon missing $word", priority.contains(word))
            val (x, y) = GlideBenchTest.gesture(
                GlideBenchTest.ru11, word, kotlin.random.Random(word.hashCode()), sigma = 0.18f
            )
            val decoded = GlideDecoder(GlideBenchTest.ru11)
                .decode(x, y, x.size, listOf(priority to 0.0))
                .map { it.word.replace('ё', 'е') }
            assertTrue("expected '$word' in top 3, got ${decoded.take(5)}",
                decoded.take(3).contains(word.replace('ё', 'е')))
        }
    }

    @Test fun aMovingTraceCanDistinguishVeryCloseAdjacentKeys() {
        val closeKeys = listOf(
            GKey('a', 100f, 100f, 100f, 150f),
            GKey('b', 115f, 100f, 100f, 150f)
        )
        val lex = Lexicon("test").apply { add("ab", 100_000L) }
        val xs = IntArray(64) { 100 + it * 15 / 63 }
        val ys = IntArray(64) { 100 }
        val result = GlideDecoder(closeKeys).decode(xs, ys, xs.size, listOf(lex to 0.0)).map { it.word }
        assertTop("ab", result)
    }

    @Test fun ruWordsWithYo() {
        assertTop("ещё".replace('ё', 'е').let { if (ru.contains("ещё")) "ещё" else it },
            decode(jcuken, wordPoints(jcuken, "еще"), listOf(ru to 0.0)), 3)
    }

    @Test fun onePointCannotSatisfyTwoVeryCloseLetters() {
        val closeKeys = listOf(
            GKey('a', 100f, 100f, 100f, 150f),
            GKey('b', 110f, 100f, 100f, 150f)
        )
        val lex = Lexicon("test").apply { add("ab", 10_000L) }
        val xs = IntArray(32) { 105 }
        val ys = IntArray(32) { 100 }
        val result = GlideDecoder(closeKeys).decode(xs, ys, xs.size, listOf(lex to 0.0))
        assertTrue("a stationary trace must not decode as two adjacent letters", result.none { it.word == "ab" })
    }

    @Test fun longWordsHaveASeparateFrequencyIndependentIndex() {
        val lex = Lexicon("ru").apply {
            add("мама", 1_000_000L)
            add("перспективно", 1L)
        }
        val longList = lex.byLongFirst['п']
        assertTrue("long-word index must include rare forms", longList != null &&
            (0 until longList.n).any { lex.word(longList.a[it]) == "перспективно" })
    }

    @Test fun trimmingAllNonLetterGivesNothing() {
        val (x, y) = path(listOf(GLOBE, SPACE))
        assertEquals(0, GlideDecoder(jcuken).decode(x, y, x.size, listOf(ru to 0.0)).size)
    }

    @Test fun nextWordPredictionsStayInThePreviousWordsScript() {
        assertEquals(
            listOf("мир", "друг"),
            GlideEngine.filterByContextScript("привет", listOf("мир", "world", "друг", "phone"))
        )
        assertEquals(
            listOf("world"),
            GlideEngine.filterByContextScript("hello", listOf("world", "мир"))
        )
    }

}
