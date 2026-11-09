package ru.klaviatura

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.klaviatura.GlideCorpusTest.Companion.noisyPoints
import ru.klaviatura.GlideDecoderTest.Companion.jcuken
import ru.klaviatura.GlideDecoderTest.Companion.path
import ru.klaviatura.GlideDecoderTest.Companion.wordPoints
import ru.klaviatura.clip.ClipKind
import ru.klaviatura.clip.Screenshots
import ru.klaviatura.glide.GlideDecoder
import ru.klaviatura.text.Profanity
import java.io.File
import kotlin.random.Random

/** 1.3.1: profanity filter, «пока» regression, reflexive verbs, screenshot path matching */
class ProfanityTest {
    private fun asset(n: String) = listOf("src/main/assets/glide/$n", "app/src/main/assets/glide/$n").map { File(it) }.first { it.exists() }
    private fun load() { Profanity.words = (asset("blocked_ru.txt").readLines() + asset("blocked_en.txt").readLines()).filter { it.isNotBlank() }.toHashSet(); Profanity.enabled = true }
    @After fun reset() { Profanity.words = emptySet(); Profanity.enabled = true }

    private fun decode(word: String, seed: Int? = null): List<String> {
        val pts = if (seed == null) wordPoints(jcuken, word) else noisyPoints(jcuken, word, Random(seed))
        val (x, y) = path(pts)
        return GlideDecoder(jcuken).decode(x, y, x.size, listOf(GlideDecoderTest.ru to 0.0, GlideDecoderTest.en to 0.3)).map { it.word }
    }

    @Test fun listsBlockObsceneButNotInnocentWords() {
        load()
        for (w in listOf("попка", "хуй", "пиздец", "блядь", "ебать", "жопа", "дерьмо", "fuck", "shit")) assertTrue(w, Profanity.blocks(w))
        for (w in listOf("пока", "поки", "употребить", "ребёнок", "ребенок", "себя", "хлеба", "небо", "оскорблять", "писать",
                "сучок", "скука", "команда", "трахея", "мандарин", "анализ", "корабль", "сосиска", "художник", "попкорн",
                "порка", "копируются", "класс", "пенистый", "сучковатый", "дрочёна", "cocktail", "pass", "assume", "analysis"))
            assertFalse(w, Profanity.blocks(w))
    }

    @Test fun filterOffAllowsEverything() {
        load(); Profanity.enabled = false
        assertFalse(Profanity.blocks("попка")); assertEquals("жопа", Profanity.mask("жопа"))
    }

    @Test fun voiceMask() {
        load()
        assertEquals("ж***,", Profanity.mask("жопа,")); assertEquals("пока", Profanity.mask("пока"))
    }

    @Test fun pokaSwipeGivesPoka() {
        load()
        assertEquals("пока", decode("пока").first())
        for (s in 0 until 6) { val r = decode("пока", s); assertEquals("seed $s: $r", "пока", r.first()); assertFalse(r.contains("попка")) }
    }

    @Test fun pokaWinsEvenWithoutFilter() {
        Profanity.enabled = false
        for (s in 0 until 6) { val r = decode("пока", s); assertEquals("seed $s: $r", "пока", r.first()) }
    }

    @Test fun pathsNearBlockedWordsNeverOfferThem() {
        load()
        for (w in listOf("попка", "писька", "жопа", "попа")) for (s in 0 until 3)
            decode(w, s).forEach { assertFalse("$w/$s -> $it", Profanity.blocks(it)) }
    }

    @Test fun reflexiveVerbs() {
        for (w in listOf("копируются", "копируется", "улыбаюсь", "встретимся", "собираемся", "смеялись", "учатся"))
            assertTrue("$w in lexicon", GlideDecoderTest.ru.contains(w))
        for (w in listOf("копируются", "копируется", "собираемся")) {
            var top3 = 0
            for (s in 0 until 3) if (decode(w, s).take(3).contains(w)) top3++
            assertTrue("$w top3 $top3/3 ${decode(w, 0).take(5)}", top3 >= 2)
        }
    }

    @Test fun yoWritten() {
        val m = ru.klaviatura.text.Yo.parse(asset("ru_yo.txt").readLines().asSequence())
        assertEquals("ещё", m["еще"]); assertEquals("её", m["ее"]); assertEquals("чёрный", m["черный"])
        assertFalse("все is ambiguous", m.containsKey("все")); assertFalse(m.containsKey("небо"))
        ru.klaviatura.text.Yo.map = m; ru.klaviatura.text.Yo.enabled = true
        assertEquals("Ещё,", ru.klaviatura.text.Yo.fix("Еще,")); assertEquals("пока", ru.klaviatura.text.Yo.fix("пока"))
        ru.klaviatura.text.Yo.enabled = false
        assertEquals("еще", ru.klaviatura.text.Yo.fix("ещё"))
        ru.klaviatura.text.Yo.enabled = true
        val lex = GlideDecoderTest.ru
        assertTrue(lex.contains("ещё")); assertFalse("е-spelling merged into ё", lex.contains("еще")); assertTrue(lex.contains("все") && lex.contains("всё"))
        assertEquals("ещё", decode("еще").first())
    }

    @Test fun byteLexiconRoundTrip() {
        val l = ru.klaviatura.glide.Lexicon.parse("x", sequenceOf("ёлка\t5", "Москва\t3", "hello\t2", "café\t1"))
        assertEquals(listOf("ёлка", "Москва", "hello", "café"), l.words.toList()); assertTrue("1.4.0: any alphabet", l.contains("café")); assertTrue(l.contains("ёлка")); assertFalse(l.contains("елка"))
    }

    @Test fun screenshotPaths() {
        assertTrue(Screenshots.isScreenshot("DCIM/Screenshots/", "Screenshot_2026-11-05-09-13-00-12.jpg"))
        assertTrue(Screenshots.isScreenshot("Pictures/Screenshots/", "x.png"))
        assertTrue(Screenshots.isScreenshot("/storage/emulated/0/DCIM/Screenshots/a.jpg", null))
        assertTrue(Screenshots.isScreenshot("DCIM/Camera/", "Screenshot_1.png"))
        assertFalse(Screenshots.isScreenshot("DCIM/Camera/", "IMG_20261105_091300.jpg"))
        assertFalse(Screenshots.isScreenshot("Pictures/Telegram/", "photo.jpg"))
    }

    @Test fun clipItemKinds() {
        assertTrue(ClipKind.isFile("content", "image/png", false, true))
        assertTrue(ClipKind.isFile("content", null, true, true)) // gallery: text/uri-list, empty text
        assertTrue(ClipKind.isFile("content", "image/jpeg", true, false))
        assertFalse(ClipKind.isFile(null, null, true, false))
        assertFalse(ClipKind.isFile("content", "text/plain", true, false))
    }
}
