package ru.klaviatura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.klaviatura.glide.GlideDecoder
import kotlin.random.Random

/**
 * 1.5.9: the swipe decoder must be fast on the real (1.3 M word) Russian lexicon and must never prefer
 * long rare words for a short swipe. Run with -Dkl.bench=1 to print timings only.
 */
class GlideSpeedTest {
    private val ru = GlideBenchTest.lex("ru")
    private val en = GlideBenchTest.lex("en")
    private val lexs = listOf(ru to 0.0, en to 0.3)

    private fun typical(): List<String> {
        val top = GlideBenchTest.top("ru", 5000)
        val rnd = Random(159)
        return (top.take(30) + List(40) { top[30 + rnd.nextInt(top.size - 30)] }).distinct()
    }

    @Test fun decodeIsFastOnBigLexicon() {
        assertTrue("lexicon must be large", ru.size >= 100_000)
        val d = GlideDecoder(GlideBenchTest.ru11)
        val words = typical()
        // warm-up (JIT)
        for (w in words.take(15)) { val (x, y) = GlideBenchTest.gesture(GlideBenchTest.ru11, w, Random(1)); d.decode(x, y, x.size, lexs) }
        val times = ArrayList<Double>(); val loose = ArrayList<Double>(); val okCost = ArrayList<Double>(); val badCost = ArrayList<Double>(); var tailN = 0; val tight = ArrayList<Double>()
        for (w in words) for (seed in 0 until 2) {
            val (x, y) = GlideBenchTest.gesture(GlideBenchTest.ru11, w, Random(w.hashCode() * 7 + seed))
            val t0 = System.nanoTime()
            val res = d.decode(x, y, x.size, lexs)
            val ms = (System.nanoTime() - t0) / 1e6
            times.add(ms)
            res.firstOrNull()?.let { (if (it.word == w) okCost else badCost).add(it.cost) }
            if (d.lastTailPass) tailN++
            (if (d.lastLoosePass) loose else tight).add(ms)
            if (ms > 50) println("[slow] $w ${"%.0f".format(ms)}ms loose=${d.lastLoosePass} scanned=${d.lastScanned} scored=${d.lastScored} pts=${x.size}")
        }
        times.sort()
        val med = times[times.size / 2]; val p95 = times[(times.size * 95) / 100]; val max = times.last()
        println("[speed] lexicon=${ru.size} n=${times.size} median=${"%.1f".format(med)}ms p95=${"%.1f".format(p95)}ms max=${"%.1f".format(max)}ms")
        println("[speed] tailPass=$tailN correct-top1 cost max=${"%.2f".format(okCost.maxOrNull() ?: 0.0)} p90=${"%.2f".format(okCost.sorted().getOrElse(okCost.size * 9 / 10) { 0.0 })} n=${okCost.size}; wrong n=${badCost.size} costs=${badCost.sorted().map { "%.2f".format(it) }}")
        println("[speed] tight-only n=${tight.size} median=${"%.1f".format(tight.sorted().getOrElse(tight.size / 2) { 0.0 })}ms; loose pass n=${loose.size} median=${"%.1f".format(loose.sorted().getOrElse(loose.size / 2) { 0.0 })}ms")
        if (System.getProperty("kl.bench") == null) {
            assertTrue("median decode ${med}ms must be < 50 ms", med < 50.0)
            assertTrue("p95 decode ${p95}ms must be < 50 ms", p95 < 50.0)
        }
    }

    /** what the keyboard really does: a 50 ms budget per gesture, so even the worst gesture cannot hang the keyboard */
    @Test fun budgetedDecodeNeverExceedsTheBudget() {
        val d = GlideDecoder(GlideBenchTest.ru11)
        val times = ArrayList<Double>()
        for (w in typical()) for (seed in 0 until 2) {
            val (x, y) = GlideBenchTest.gesture(GlideBenchTest.ru11, w, Random(w.hashCode() * 7 + seed))
            val t0 = System.nanoTime()
            d.decode(x, y, x.size, lexs, deadlineNs = t0 + 50_000_000L)
            times.add((System.nanoTime() - t0) / 1e6)
        }
        times.sort()
        println("[speed] budgeted(50ms) median=${"%.1f".format(times[times.size / 2])}ms p95=${"%.1f".format(times[times.size * 95 / 100])}ms max=${"%.1f".format(times.last())}ms")
        assertTrue("budgeted max ${times.last()}ms", times.last() < 75.0)
    }

    @Test fun timeBudgetStopsTheScan() {
        val d = GlideDecoder(GlideBenchTest.ru11)
        val (x, y) = GlideBenchTest.gesture(GlideBenchTest.ru11, "поговорить", Random(5))
        d.decode(x, y, x.size, lexs) // warm-up
        val t0 = System.nanoTime()
        d.decode(x, y, x.size, lexs, deadlineNs = System.nanoTime()) // budget already used up
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue("an exhausted budget must stop the scan after one chunk per letter, took ${ms}ms", ms < 40.0)
        assertTrue(d.lastTruncated)
    }

    @Test fun staleGestureIsCancelled() {
        val d = GlideDecoder(GlideBenchTest.ru11)
        val (x, y) = GlideBenchTest.gesture(GlideBenchTest.ru11, "привет", Random(6))
        val res = d.decode(x, y, x.size, lexs, abort = { true })
        assertTrue(res.isEmpty())
        assertTrue(d.lastAborted)
        assertTrue(d.decode(x, y, x.size, lexs).isNotEmpty())
        assertTrue(!d.lastAborted)
    }

    /** the lexicon is scanned most-frequent-first, so a truncated scan still finds common words */
    @Test fun truncatedScanStillFindsCommonWords() {
        val d = GlideDecoder(GlideBenchTest.ru11)
        var ok = 0; val words = listOf("привет", "спасибо", "хорошо", "сегодня", "работа", "дорога", "вопрос", "человек")
        for (w in words) {
            val (x, y) = GlideBenchTest.gesture(GlideBenchTest.ru11, w, Random(w.hashCode()))
            val r = d.decode(x, y, x.size, lexs, deadlineNs = System.nanoTime() + 3_000_000L)
            if (r.take(3).any { it.word == w }) ok++
        }
        assertTrue("a 3 ms budget should still find most common words, found $ok/${words.size}", ok >= words.size - 2)
    }

    /** long rare forms («рассогласованность») must not beat the common word for a short swipe */
    @Test fun longRareWordsDoNotWin() {
        val d = GlideDecoder(GlideBenchTest.ru11)
        val common = listOf("рассказ", "расскажи", "рассказал", "рассвет", "расстояние", "рассмотрим", "россия", "работа", "разговор", "результат")
        var bad = 0
        for (w in common) for (seed in 0 until 3) {
            val (x, y) = GlideBenchTest.gesture(GlideBenchTest.ru11, w, Random(w.hashCode() + seed))
            val r = d.decode(x, y, x.size, lexs).map { it.word }
            val top = r.firstOrNull() ?: ""
            if (top != w && top.length >= w.length + 5) bad++
        }
        println("[rare] long-rare-winner count=$bad of ${common.size * 3}")
        assertEquals("a much longer word won against the swiped word", 0, bad)
    }
}
