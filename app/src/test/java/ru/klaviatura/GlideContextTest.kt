package ru.klaviatura

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.klaviatura.GlideCorpusTest.Companion.lex
import ru.klaviatura.GlideCorpusTest.Companion.noisyPoints
import ru.klaviatura.GlideDecoderTest.Companion.jcuken
import ru.klaviatura.GlideDecoderTest.Companion.path
import ru.klaviatura.glide.BigramLM
import ru.klaviatura.glide.GlideDecoder
import ru.klaviatura.glide.GlideLearning
import ru.klaviatura.glide.GlideEngine
import java.io.File
import kotlin.random.Random

/** base bigram LM must fix ambiguous swipes using the previous word, without breaking unambiguous ones */
class GlideContextTest {
    private fun lm(): BigramLM {
        val f = listOf("src/main/assets/glide/ru_bigrams.tsv", "app/src/main/assets/glide/ru_bigrams.tsv").map { File(it) }.first { it.exists() }
        return BigramLM.parse(f.readLines().asSequence())
    }

    private class Ctx(val lm: BigramLM) : GlideLearning {
        override fun wordBoost(word: String) = 0.0
        override fun bigramBoost(prev: String?, word: String) = GlideDecoder.CTX_W * lm.score(prev, word)
        override fun isForgotten(word: String) = false
        override val biasX = 0f
        override val biasY = 0f
    }

    @Test fun unwantedUkraineContinuationIsFilteredAfterHelpForms() {
        val forms = listOf("помощь", "помощи", "помощью", "помощей", "помощам", "помощами", "помощах")
        forms.forEach { form ->
            assertTrue("$form should not predict Украине", GlideEngine.isUnwantedHelpUkrainePair(form, "Украине"))
            assertFalse("$form should not block unrelated words", GlideEngine.isUnwantedHelpUkrainePair(form, "Украина"))
        }
        assertFalse(GlideEngine.isUnwantedHelpUkrainePair("здоровье", "Украине"))
    }

    @Test fun contextFixesAmbiguousSwipes() {
        val lm = lm(); val ru = lex("ru"); val d = GlideDecoder(jcuken); val ctx = Ctx(lm)
        // natural phrases; second word is swiped, first is context
        val pairs = listOf("я пошёл", "все были", "в течение", "так что", "может быть", "у меня", "не было",
            "что он", "по крайней", "в том", "так и", "до сих", "на самом", "в конце", "при этом", "кроме того",
            "не менее", "на эти", "черном море", "напомним ранее", "из главных", "самое главное",
            "в виде", "как раз", "в рамках", "в частности", "у нас", "мы были", "это было", "он сказал",
            "она была", "в россии", "в городе", "на улице", "в прошлом", "в этом", "по словам", "в результате")
        var base = 0; var withCtx = 0; var n = 0
        for ((k, p) in pairs.withIndex()) {
            val (prev, w) = p.split(' ')
            for (s in 0 until 3) {
                val (x, y) = path(noisyPoints(jcuken, w, Random(k * 31 + s)))
                val a = d.decode(x, y, x.size, listOf(ru to 0.0)).firstOrNull()?.word?.replace('ё', 'е')
                val b = d.decode(x, y, x.size, listOf(ru to 0.0), ctx, prev).firstOrNull()?.word?.replace('ё', 'е')
                val t = w.replace('ё', 'е'); n++
                if (a == t) base++; if (b == t) withCtx++
                if (a != b) println("[ctx] '$prev' + $w: $a -> $b")
            }
        }
        println("[ctx] top1 no-context ${base}/$n, with context ${withCtx}/$n")
        println("[ctx] next after 'у': ${lm.next("у", 5)}, after 'может': ${lm.next("может", 3)}")
        assertTrue("context must help", withCtx >= base + 3)
    }
}
