package ru.klaviatura

import org.junit.Assert.assertTrue
import org.junit.Test
import ru.klaviatura.glide.GlideDecoder
import ru.klaviatura.glide.Lexicon
import java.io.File

/** Benchmark: swipe accuracy for programming keywords (Latin + Понятный). */
class CodeSwipeBenchTest {
    private fun asset(name: String) = listOf("src/main/assets/glide/$name", "app/src/main/assets/glide/$name")
        .map { File(it) }.first { it.exists() }

    @Test fun codeKeywordBenchmark() {
        val code = Lexicon.parse("code", asset("code.tsv").readLines().asSequence())
        val en = try { Lexicon.parse("en", asset("en.tsv").readLines().asSequence()) } catch (_: Exception) { Lexicon("en") }
        val latin = listOf(
            "if", "else", "for", "while", "return", "function", "class", "const", "var", "let", "def",
            "import", "from", "true", "false", "null", "this", "new", "int", "string", "list", "map",
            "print", "async", "await", "public", "private", "static", "void", "try", "catch", "throw",
            "switch", "case", "break", "continue", "package", "interface", "extends", "implements",
            "select", "where", "insert", "update", "delete", "create", "table", "join",
            "fun", "val", "override", "suspend", "println", "length", "append", "push", "filter",
            "reduce", "promise", "export", "default", "typeof", "undefined", "document", "window",
            "console", "json", "http", "request", "response", "error", "result", "main"
        )
        val ru = listOf(
            "если", "иначе", "пока", "повтори", "функция", "класс", "пусть", "конст", "верни",
            "конец", "пиши", "напиши", "истина", "ложь", "ничего", "стоп", "дальше", "подключи",
            "список", "словарь", "строка", "число", "длина", "добавь", "убери", "пробуй", "выбор"
        )
        for (w in latin + ru) assertTrue("lexicon missing $w", code.contains(w) || code.contains(w.lowercase()))

        val ruL = listOf(code to 0.0, en to 0.25)
        val rLat = GlideBenchTest.run("CODE latin", GlideBenchTest.qwerty, latin, ruL, seeds = 2)
        val rRu = GlideBenchTest.run("CODE ponyatny", GlideBenchTest.ru11, ru, ruL, seeds = 2)
        println(rLat); println("  misses: " + rLat.misses.take(20).joinToString("; "))
        println(rRu); println("  misses: " + rRu.misses.take(20).joinToString("; "))
        val n = rLat.n + rRu.n
        val t1 = rLat.t1 + rRu.t1
        val t3 = rLat.t3 + rRu.t3
        val top1 = 100.0 * t1 / n
        val top3 = 100.0 * t3 / n
        println("[CODE TOTAL] top1=${"%.1f".format(top1)}% top3=${"%.1f".format(top3)}% (n=$n)")
        assertTrue("code swipe top-1 $top1% < 85%", top1 >= 85.0)
        assertTrue("code swipe top-3 $top3% < 93%", top3 >= 93.0)
    }
}
