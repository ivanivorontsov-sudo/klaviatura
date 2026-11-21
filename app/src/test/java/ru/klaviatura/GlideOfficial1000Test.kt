package ru.klaviatura

import org.junit.Assert.assertTrue
import org.junit.Test
import ru.klaviatura.glide.Lexicon

/** Fixed 1000-word official swipe benchmark (800 RU + 200 EN, noisy synthetic paths). */
class GlideOfficial1000Test {
    @Test fun official1000() {
        val ru = GlideBenchTest.lex("ru"); val en = GlideBenchTest.lex("en")
        val ruW = GlideBenchTest.top("ru", 800)
        val enW = GlideBenchTest.top("en", 200)
        val ruL = listOf(ru to 0.0, en to 0.3)
        val enL = listOf(en to 0.0, ru to 0.3)
        val r1 = GlideBenchTest.run("RU-800", GlideBenchTest.ru11, ruW, ruL, seeds = 1)
        val r2 = GlideBenchTest.run("EN-200", GlideBenchTest.qwerty, enW, enL, seeds = 1)
        val n = r1.n + r2.n; val t1 = r1.t1 + r2.t1; val t3 = r1.t3 + r2.t3
        println(r1); println(r2)
        println("[official1000] top1=${"%.1f".format(100.0*t1/n)}% top3=${"%.1f".format(100.0*t3/n)}% (n=$n)")
        println("  RU misses: " + r1.misses.take(15).joinToString("; "))
        println("  EN misses: " + r2.misses.take(15).joinToString("; "))
        assertTrue("official 1000 top-1 ${100.0*t1/n}% < 90%", 100.0 * t1 / n >= 90.0)
        assertTrue("official 1000 top-3 ${100.0*t3/n}% < 96%", 100.0 * t3 / n >= 96.0)
    }
}
