package ru.klaviatura

import org.junit.Assert.assertTrue
import org.junit.Test
import ru.klaviatura.GlideDecoderTest.Companion.jcuken
import ru.klaviatura.GlideDecoderTest.Companion.qwerty
import ru.klaviatura.GlideDecoderTest.Companion.path
import ru.klaviatura.glide.GKey
import ru.klaviatura.glide.GlideDecoder
import ru.klaviatura.glide.Lexicon
import java.io.File
import kotlin.random.Random

/** accuracy benchmark: 60 RU words (rare + inflected forms) + 20 EN, 3 noisy swipes each */
class GlideCorpusTest {
    companion object {
        val RU = listOf(
            // inflected forms
            "переписывались", "пообещала", "задумавшись", "непременно", "вскарабкался", "сфотографировала",
            "договорились", "приготовлю", "разговаривали", "позвонишь", "напишите", "отправлю", "ответила",
            "увидимся", "соскучилась", "приедешь", "забыли", "купила", "посмотрим", "узнаешь", "встретимся",
            "дождёмся", "проснулся", "заболела", "собираемся", "поздравляю", "извините", "пожалуйста",
            "студентами", "книгами", "деревьями", "друзьям", "детьми", "сообщения", "машиной", "квартиры",
            "вечером", "завтрашний", "интересного", "хорошему", "последних", "красивыми", "маленькой",
            // rare words
            "щеколда", "выхухоль", "эклектика", "фьорд", "въедливый", "абрикосовый", "изюминка",
            "жёлудь", "гиппопотам", "щупальце", "сюрреализм", "чехарда", "брезгливость", "акварель",
            "шпаргалка", "ухабистый", "эскимо",
            // modern / slang / loanwords (1.2.2)
            "свайпом", "свайпнуть", "скриншотом", "смартфоне", "гитхабе", "браузере", "апдейтов", "чатике",
            "лайкнула", "загуглил", "стримы", "сторис", "кринжово", "дедлайна", "созвонимся")
        val EN = listOf("wonderful", "keyboards", "happened", "beautifully", "tomorrow", "yesterday", "anything",
            "remember", "different", "questions", "something", "together", "everyone", "probably",
            "thoughtful", "squirrel", "rhythm", "jealousy", "quickly", "excited")

        fun lex(lang: String): Lexicon {
            val f = System.getProperty("kl.lex.$lang")?.let { File(it) }
                ?: listOf("src/main/assets/glide/$lang.tsv", "app/src/main/assets/glide/$lang.tsv").map { File(it) }.first { it.exists() }
            return Lexicon.parse(lang, f.readLines().asSequence())
        }

        fun noisyPoints(keys: List<GKey>, word: String, rnd: Random) = word.replace('ё', 'е').map { c ->
            val k = keys.first { it.ch == c }
            (k.cx + (rnd.nextFloat() - 0.5f) * 0.6f * k.w) to (k.cy + (rnd.nextFloat() - 0.5f) * 0.6f * k.h)
        }
    }

    private fun run(keys: List<GKey>, words: List<String>, lexs: List<Pair<Lexicon, Double>>, tag: String): Pair<Double, Double> {
        var t1 = 0; var t3 = 0; var n = 0; val fails = ArrayList<String>()
        val d = GlideDecoder(keys)
        for (w in words) for (seed in 0 until 3) {
            val (x, y) = path(noisyPoints(keys, w, Random(w.hashCode() * 31 + seed)))
            val r = d.decode(x, y, x.size, lexs).map { it.word.replace('ё', 'е') }
            val ww = w.replace('ё', 'е')
            n++; if (r.firstOrNull() == ww) t1++; if (r.take(3).contains(ww)) t3++ else fails.add("$w→${r.take(3)}")
        }
        println("[$tag] top1=${"%.1f".format(100.0 * t1 / n)}% top3=${"%.1f".format(100.0 * t3 / n)}% (n=$n)")
        println("[$tag] misses: " + fails.joinToString("; "))
        return t1.toDouble() / n to t3.toDouble() / n
    }

    @Test fun corpusAccuracy() {
        val ru = lex("ru"); val en = lex("en")
        val missingRu = RU.filter { !ru.contains(it) && !ru.contains(it.replace('ё', 'е')) }
        println("[corpus] RU words not in lexicon: ${missingRu.size} $missingRu")
        val (r1, r3) = run(jcuken, RU, listOf(ru to 0.0, en to 0.3), "RU")
        val (e1, e3) = run(qwerty, EN, listOf(en to 0.0, ru to 0.3), "EN")
        println("[corpus] TOTAL top1=${"%.1f".format(100 * (r1 * RU.size + e1 * EN.size) / (RU.size + EN.size))}% top3=${"%.1f".format(100 * (r3 * RU.size + e3 * EN.size) / (RU.size + EN.size))}%")
        assertTrue("min threshold", System.getProperty("kl.bench") != null || r3 >= 0.80)
    }

    @Test fun oovGuessFollowsLetters() {
        val d = GlideDecoder(jcuken)
        var ok = 0
        for (w in listOf("свайп", "кринж", "лол", "мем", "зум")) {
            val (x, y) = path(noisyPoints(jcuken, w, Random(7)))
            val g = d.rawGuess(x, y, x.size)
            println("[oov] $w -> $g"); if (g == w) ok++
        }
        assertTrue("oov guesses", ok >= 3)
    }
}
