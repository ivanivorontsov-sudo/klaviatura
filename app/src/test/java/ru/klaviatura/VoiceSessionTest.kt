package ru.klaviatura

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.klaviatura.voice.Asr
import ru.klaviatura.voice.VoiceOutput
import ru.klaviatura.voice.VoiceSession
import ru.klaviatura.voice.VoiceText

class VoiceSessionTest {
    /** replays recognizer outputs: each step is either partial text or a final result (word to conf) */
    class Fake(val steps: List<Any>) : Asr {
        var i = -1
        override fun accept(buf: ShortArray, n: Int): Boolean { i++; return steps.getOrNull(i) is List<*> }
        override fun partial() = """{"partial":"${steps.getOrNull(i) as? String ?: ""}"}"""
        override fun result(): String = json(steps[i] as List<*>)
        override fun final(): String = """{"text":""}"""
        override fun close() {}
        private fun json(ws: List<*>) = "{\"result\":[" + ws.joinToString(",") { val (w, c) = it as Pair<*, *>; "{\"word\":\"$w\",\"conf\":$c}" } +
            "],\"text\":\"" + ws.joinToString(" ") { (it as Pair<*, *>).first.toString() } + "\"}"
    }
    class Ed : VoiceOutput {
        val text = StringBuilder(); var comp = ""; val partials = ArrayList<String>()
        override fun partial(text: String) { comp = text; partials.add(text) }
        override fun commit(text: String) { comp = ""; if (this.text.isNotEmpty()) this.text.append(' '); this.text.append(text) }
    }

    @Test fun partialsReplaceAndOnlyFinalIsCommitted() {
        val ed = Ed()
        val s = VoiceSession(listOf("ru" to Fake(listOf("один", "один два", "один два три",
            listOf("один" to 1.0, "два" to 0.9, "три" to 0.95, "четыре" to 0.9)))), ed)
        repeat(4) { s.feed(ShortArray(10), 10) }
        assertEquals("один два три четыре", ed.text.toString())
        assertEquals("", ed.comp)
        assertEquals(listOf("один", "один два", "один два три"), ed.partials.filter { it.isNotEmpty() })
    }

    @Test fun noiseLoopIsRemoved() {
        val ed = Ed()
        val s = VoiceSession(listOf("ru" to Fake(listOf(
            listOf("один" to 1.0, "ты" to 0.3, "четыре" to 0.9, "ты" to 0.35, "ты" to 0.3, "ты" to 0.2)))), ed)
        s.feed(ShortArray(10), 10)
        assertEquals("один четыре", ed.text.toString())
    }

    @Test fun repeatsCollapsed() {
        assertEquals("один ты", VoiceText.format("один ты ты ты ты", "ru", false))
        assertEquals("да да", VoiceText.format("да да", "ru", false))
        assertEquals("Привет, как дела?".lowercase(), VoiceText.format("привет запятая как дела вопросительный знак", "ru", true).lowercase())
    }
}
