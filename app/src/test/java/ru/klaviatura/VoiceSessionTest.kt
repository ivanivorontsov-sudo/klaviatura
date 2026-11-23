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
        override fun command(text: String) = false
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

    @Test fun cumulativePartialsDoNotRepeatManuallyFrozenPrefix() {
        assertEquals("салфеткой", VoiceText.afterCommittedPrefix("спиртовой салфеткой", "спиртовой"))
        assertEquals("салфеткой", VoiceText.afterCommittedPrefix("СПИРТОВОЙ салфеткой", "спиртовой"))
        assertEquals("", VoiceText.afterCommittedPrefix("спиртовой", "спиртовой"))
        assertEquals("салфеткой потом", VoiceText.afterCommittedPrefix("спиртовой салфеткой потом", "спиртовой"))
        // A recognizer may revise a manually committed prefix; never replay it at the new caret.
        assertEquals("салфеткой", VoiceText.afterCommittedPrefix("спиртовая салфеткой", "спиртовой"))
    }

    @Test fun noiseLoopIsRemoved() {
        val ed = Ed()
        val s = VoiceSession(listOf("ru" to Fake(listOf(
            listOf("один" to 1.0, "ты" to 0.3, "четыре" to 0.9, "ты" to 0.35, "ты" to 0.3, "ты" to 0.2)))), ed)
        s.feed(ShortArray(10), 10)
        assertEquals("один четыре", ed.text.toString())
    }

    @Test fun russianAiAbbreviationIsJoinedOnlyInClearContext() {
        assertEquals("как ИИ работает", VoiceText.format("как и и работает", "ru", false))
        assertEquals("чай и и кофе", VoiceText.format("чай и и кофе", "ru", false))
    }

    @Test fun aiInitialismKeepsUppercaseAndDoesNotMergeNormalConjunctions() {
        assertEquals("ИИ работает", VoiceText.format("ии работает", "ru", false))
        assertEquals("как ИИ работает", VoiceText.format("как и и работает", "ru", false))
        assertEquals("чай и и кофе", VoiceText.format("чай и и кофе", "ru", false))
        assertEquals("ИИ, работает", VoiceText.format("ии, работает", "ru", false))
    }

    @Test fun voiceInputWordIsRepairedOnlyInVoiceContext() {
        assertEquals("голосовой ввод работает", VoiceText.format("голосовой вот работает", "ru", false))
        assertEquals("голосовой ввод работает", VoiceText.format("голосовой вод работает", "ru", false))
        assertEquals("вот это работает", VoiceText.format("вот это работает", "ru", false))
        assertEquals("воды много", VoiceText.format("воды много", "ru", false))
    }

    @Test fun mixedModeChoosesRussianWhenEnglishModelHasHigherConfidence() {
        val ed = Ed()
        val english = Fake(listOf(listOf("yellow" to 0.99, "birds" to 0.98, "fly" to 0.97)))
        val russian = Fake(listOf(listOf("я" to 0.72, "хочу" to 0.70, "говорить" to 0.69, "сегодня" to 0.68)))
        val session = VoiceSession(listOf("en" to english, "ru" to russian), ed)
        session.feed(ShortArray(10), 10)
        assertEquals("привет мир", ed.text.toString())
    }

    @Test fun mixedModeShowsPartialFromLanguageMatchingItsScript() {
        val ed = Ed()
        val english = Fake(listOf("yellow birds flying"))
        val russian = Fake(listOf("я хочу говорить"))
        val session = VoiceSession(listOf("en" to english, "ru" to russian), ed)
        session.feed(ShortArray(10), 10)
        assertEquals("привет мир", ed.comp)
    }


    @Test fun mixedModeCanSwitchToEnglishWhenItHasClearLanguageEvidence() {
        val ed = Ed()
        val russian = Fake(listOf(listOf("заработанный" to 0.99, "ветер" to 0.98, "летит" to 0.97)))
        val english = Fake(listOf(listOf("hello" to 0.80, "my" to 0.79, "name" to 0.78, "is" to 0.77)))
        val session = VoiceSession(listOf("ru" to russian, "en" to english), ed)
        session.feed(ShortArray(10), 10)
        assertEquals("hello my name is", ed.text.toString())
    }

    @Test fun scriptCompatibilityScoresCyrillicAndLatinIndependently() {
        assertEquals(1.0, VoiceText.scriptCompatibility("Привет мир", "ru"), 0.0)
        assertEquals(1.0, VoiceText.scriptCompatibility("hello world", "en"), 0.0)
        assertEquals(0.0, VoiceText.scriptCompatibility("Привет мир", "en"), 0.0)
        assertEquals(0.0, VoiceText.scriptCompatibility("hello world", "ru"), 0.0)
    }

    @Test fun repeatsCollapsed() {
        assertEquals("один ты", VoiceText.format("один ты ты ты ты", "ru", false))
        assertEquals("да да", VoiceText.format("да да", "ru", false))
        assertEquals("Привет, как дела?".lowercase(), VoiceText.format("привет запятая как дела вопросительный знак", "ru", true).lowercase())
    }
    @Test fun ordinalEditCommandFindsWord() {
        val source = "один два три четыре"
        val edit = ru.klaviatura.voice.VoiceEditCommands.parse(source, "исправь третье по счету слово на ВОСЕМЬ")!!
        assertEquals("три", source.substring(edit.start, edit.end))
    }

    @Test fun rewriteCommandRequiresWholeField() {
        assertEquals(null, ru.klaviatura.voice.VoiceEditCommands.parse("часть текста", "напиши заново Привет", false))
        val edit = ru.klaviatura.voice.VoiceEditCommands.parse("Старый текст", "напиши заново Новый текст")!!
        assertEquals("Новый текст", edit.replacement)
    }
}
