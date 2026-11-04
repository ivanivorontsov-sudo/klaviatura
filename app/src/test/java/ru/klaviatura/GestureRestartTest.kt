package ru.klaviatura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.klaviatura.GlideCorpusTest.Companion.lex
import ru.klaviatura.GlideCorpusTest.Companion.noisyPoints
import ru.klaviatura.GlideDecoderTest.Companion.jcuken
import ru.klaviatura.GlideDecoderTest.Companion.path
import ru.klaviatura.glide.GestureRestart
import ru.klaviatura.glide.GlideDecoder
import ru.klaviatura.glide.ProperNouns
import ru.klaviatura.voice.VoiceText
import kotlin.random.Random

class GestureRestartTest {
    private val kw = GlideDecoder(jcuken).kw

    /** path for [word] with [dwellMs] of stillness at letter index [dwellAt]; 10 ms per sample */
    private fun timed(word: String, dwellAt: Int = -1, dwellMs: Int = 0, seed: Int = 1): Triple<IntArray, IntArray, IntArray> {
        val (x, y) = path(noisyPoints(jcuken, word, Random(seed)))
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>(); val ts = ArrayList<Int>(); var t = 0
        // locate sample closest to the dwell letter
        val target = if (dwellAt >= 0) jcuken.first { it.ch == word[dwellAt] } else null
        var bestI = -1
        if (target != null) { var bd = Float.MAX_VALUE
            for (i in x.indices) { val d = (x[i] - target.cx) * (x[i] - target.cx) + (y[i] - target.cy) * (y[i] - target.cy); if (d < bd) { bd = d; bestI = i } } }
        for (i in x.indices) {
            xs.add(x[i]); ys.add(y[i]); ts.add(t); t += 10
            if (i == bestI) repeat(dwellMs / 10) { xs.add(x[i] + (it % 2)); ys.add(y[i]); ts.add(t); t += 10 }
        }
        return Triple(xs.toIntArray(), ys.toIntArray(), ts.toIntArray())
    }

    private fun concat(a: Triple<IntArray, IntArray, IntArray>, b: Triple<IntArray, IntArray, IntArray>): Triple<IntArray, IntArray, IntArray> {
        val off = a.third.last() + 10
        return Triple(a.first + b.first, a.second + b.second, a.third + b.third.map { it + off })
    }

    @Test fun holdRestartsGesture() {
        val ru = lex("ru"); val d = GlideDecoder(jcuken)
        // wrong start «кошк…», finger slides to «м» and is held there 600 ms, then «мышка» from there
        val wrong = timed("кошкм", 4, 600)
        val (xs, ys, ts) = concat(wrong, timed("мышка", seed = 2))
        val st = GestureRestart.holdStart(xs, ys, ts, xs.size, kw, 450)
        assertTrue("restart found", st > 0)
        // same gesture but without samples during the hold (only a time gap), as Android may deliver it
        val gx = ArrayList<Int>(); val gy = ArrayList<Int>(); val gt = ArrayList<Int>(); var last = -1
        for (i in xs.indices) { if (last >= 0 && xs[i] - xs[last] in -1..1 && ys[i] == ys[last]) continue; gx.add(xs[i]); gy.add(ys[i]); gt.add(ts[i]); last = i }
        assertTrue("gap hold found", GestureRestart.holdStart(gx.toIntArray(), gy.toIntArray(), gt.toIntArray(), gx.size, kw, 450) > 0)
        val top = d.decode(xs.copyOfRange(st, xs.size), ys.copyOfRange(st, ys.size), xs.size - st, listOf(ru to 0.0)).first().word
        println("[restart] hold -> $top"); assertEquals("мышка", top)
    }

    @Test fun shortPauseAndWobbleAreNotRestart() {
        // 600 ms pause (below the 1 s default) and a slow wobbly finger must not restart
        val (xs, ys, ts) = timed("привет", 3, 600)
        assertEquals(0, GestureRestart.holdStart(xs, ys, ts, xs.size, kw, GestureRestart.DEFAULT_HOLD_MS))
        assertEquals(0, GestureRestart.holdStart(xs, ys, ts, xs.size, kw, 0)) // Off
        for (w in listOf("привет", "пока", "спасибо", "хорошо"))
            for (s in 0 until 3) { val (x, y) = path(noisyPoints(jcuken, w, Random(s)))
                assertTrue("$w/$s no return-restart", GestureRestart.returnStarts(x, y, x.size, kw).isEmpty()) }
    }

    @Test fun longHoldStillRestartsAtDefault() {
        val (xs, ys, ts) = concat(timed("кошкм", 4, 1200), timed("мышка", seed = 2))
        assertTrue(GestureRestart.holdStart(xs, ys, ts, xs.size, kw, GestureRestart.DEFAULT_HOLD_MS) > 0)
    }

    @Test fun doubleLetterPauseIsNotRestart() {
        for ((w, i) in listOf("класс" to 3, "ванна" to 2, "суббота" to 2, "программа" to 4)) {
            val (xs, ys, ts) = timed(w, i, 250)
            assertEquals("no restart for $w", 0, GestureRestart.holdStart(xs, ys, ts, xs.size, kw, 450))
        }
    }

    @Test fun returnToStartDecodesLatestSegment() {
        val ru = lex("ru"); val d = GlideDecoder(jcuken)
        // started «привет», went back to «п» and swiped «пока»
        // started «приве…», went back to «п» and swiped «пока» in one continuous gesture
        val (xs, ys) = path(noisyPoints(jcuken, "пожалуйстапока", Random(3)))
        val full = d.decode(xs, ys, xs.size, listOf(ru to 0.0))
        val starts = GestureRestart.returnStarts(xs, ys, xs.size, kw)
        assertTrue("restart candidates", starts.isNotEmpty())
        var res = full
        for (st in starts.asReversed()) {
            val seg = d.decode(xs.copyOfRange(st, xs.size), ys.copyOfRange(st, ys.size), xs.size - st, listOf(ru to 0.0))
            if (seg.isNotEmpty() && (res.isEmpty() || seg[0].cost < res[0].cost - GestureRestart.SEGMENT_MARGIN)) { res = seg; break }
        }
        println("[restart] return: full=${full.firstOrNull()?.word} -> ${res.first().word}")
        assertEquals("пока", res.first().word)
    }

    @Test fun normalWordsThatReturnToStartStayWhole() {
        val ru = lex("ru"); val d = GlideDecoder(jcuken)
        for (w in listOf("потоп", "шалаш", "казак", "наган", "радар")) {
            val (xs, ys) = path(noisyPoints(jcuken, w, Random(5)))
            var res = d.decode(xs, ys, xs.size, listOf(ru to 0.0))
            for (st in GestureRestart.returnStarts(xs, ys, xs.size, kw).asReversed()) {
                val seg = d.decode(xs.copyOfRange(st, xs.size), ys.copyOfRange(st, ys.size), xs.size - st, listOf(ru to 0.0))
                if (seg.isNotEmpty() && (res.isEmpty() || seg[0].cost < res[0].cost - GestureRestart.SEGMENT_MARGIN)) { res = seg; break }
            }
            println("[restart] $w -> ${res.firstOrNull()?.word}")
            assertEquals(w, res.first().word)
        }
    }

    @Test fun properNounsCapitalized() {
        ProperNouns.forms = java.io.File(listOf("src/main/assets/glide/ru_proper.txt", "app/src/main/assets/glide/ru_proper.txt").first { java.io.File(it).exists() }).readLines().toHashSet()
        assertEquals("Владивостоке", ProperNouns.case("владивостоке"))
        assertEquals("Ивану", ProperNouns.case("ивану"))
        assertEquals("Москвы", ProperNouns.case("москвы"))
        assertEquals("вера", ProperNouns.case("вера")) // common noun reading exists
        assertEquals("дом", ProperNouns.case("дом"))
        assertEquals("Санкт-Петербурге", ProperNouns.case("санкт-петербурге"))
        assertEquals("я живу во Владивостоке. Завтра позвоню Ивану из Москвы",
            VoiceText.format("я живу во владивостоке точка завтра позвоню ивану из москвы", "ru", commands = true))
    }
}

class ContextCapsTest {
    @Test fun ambiguousNamesNeedContext() {
        assertEquals("вера", ProperNouns.case("вера", "моя"))
        assertEquals("Вера", ProperNouns.case("вера", "привет"))
        assertEquals("Надежде", ProperNouns.case("надежде", "позвони"))
        assertEquals("надежда", ProperNouns.case("надежда", "последняя"))
        assertEquals("верой", ProperNouns.case("верой", "с")) // «с верой в победу»
        assertEquals("любовь", ProperNouns.case("любовь", "с")) 
        assertEquals("Розе", ProperNouns.case("розе", "тёте"))
        assertEquals("роза", ProperNouns.case("роза", null))
    }

    @Test fun klipyParse() {
        val body = """{"result":true,"data":{"data":[{"id":1,"file":{"md":{"gif":{"url":"https://x/md.gif"}},"sm":{"jpg":{"url":"https://x/sm.jpg"},"gif":{"url":"https://x/sm.gif"}}}},{"id":2,"file":{}}]}}"""
        val r = ru.klaviatura.emoji.KlipyApi.parse(body)
        assertEquals(listOf("https://x/sm.jpg" to "https://x/md.gif"), r)
    }
}
