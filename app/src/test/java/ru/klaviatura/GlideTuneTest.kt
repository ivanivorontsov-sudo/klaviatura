package ru.klaviatura

import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.klaviatura.glide.GlideDecoder
import java.io.File
import kotlin.random.Random

/** weight tuning on a DEV set (other words / seeds than the benchmark). Only runs with env KL_TUNE=1. */
class GlideTuneTest {
    @Test fun tune() {
        assumeTrue(System.getenv("KL_TUNE") == "1")
        val ru = GlideBenchTest.lex("ru"); val en = GlideBenchTest.lex("en")
        val rnd = Random(777)
        val ruTop = GlideBenchTest.top("ru", 5000); val enTop = GlideBenchTest.top("en", 5000)
        val ruW = List(260) { ruTop[rnd.nextInt(ruTop.size)] }.distinct() + listOf("какой-то", "по-своему", "где-нибудь", "из-за")
        val enW = List(140) { enTop[rnd.nextInt(enTop.size)] }.distinct()
        val ruG = ruW.map { w -> w to GlideBenchTest.gesture(GlideBenchTest.ru11, w, Random(w.hashCode() + 9001)) }
        val enG = enW.map { w -> w to GlideBenchTest.gesture(GlideBenchTest.qwerty, w, Random(w.hashCode() + 9001)) }
        val dRu = GlideDecoder(GlideBenchTest.ru11); val dEn = GlideDecoder(GlideBenchTest.qwerty)
        val ruL = listOf(ru to 0.0, en to 0.3); val enL = listOf(en to 0.0, ru to 0.3)
        fun eval(): Double {
            var s = 0.0
            for ((w, g) in ruG) { val r = dRu.decode(g.first, g.second, g.first.size, ruL, null, null, 3).map { it.word }; if (r.firstOrNull() == w) s += 1.0 else if (w in r) s += 0.3 }
            for ((w, g) in enG) { val r = dEn.decode(g.first, g.second, g.first.size, enL, null, null, 3).map { it.word }; if (r.firstOrNull() == w) s += 1.0 else if (w in r) s += 0.3 }
            return s / (ruG.size + enG.size)
        }
        val p = GlideDecoder.P
        val fields = GlideDecoder.Params::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        fun get(f: java.lang.reflect.Field) = (f.get(p) as Number).toDouble()
        fun set(f: java.lang.reflect.Field, v: Double) { if (f.type == java.lang.Float.TYPE) f.setFloat(p, v.toFloat()) else f.setDouble(p, v) }
        var best = eval()
        val out = File("/tmp/kb/tune.txt")
        out.writeText("start $best\n")
        for (round in 0 until (System.getenv("KL_TUNE_ROUNDS")?.toInt() ?: 3)) {
            val factors = if (round == 0) listOf(0.6, 1.6) else listOf(0.8, 1.25)
            for (f in fields) {
                val v0 = get(f)
                for (fac in factors) {
                    set(f, v0 * fac)
                    val sc = eval()
                    if (sc > best + 1e-9) { best = sc; out.appendText("r$round ${f.name}=${get(f)} -> $best\n"); break } else set(f, v0)
                }
            }
            out.appendText("round $round: " + fields.joinToString(" ") { "${it.name}=${"%.3f".format(get(it))}" } + " score=$best\n")
        }
    }
}
