package ru.klaviatura

import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import java.io.File

class ProgrammingLayoutTest {
    private fun load(name: String): String = listOf(
        "src/main/assets/layouts/main/$name",
        "app/src/main/assets/layouts/main/$name"
    ).map { File(it) }.first { it.exists() }.readText()

    private fun labels(json: String): Set<String> {
        val s = HashSet<String>()
        fun walk(v: Any?) {
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) walk(v.get(i))
                is org.json.JSONObject -> {
                    if (v.has("label")) s.add(v.getString("label"))
                    val keys = v.keys()
                    while (keys.hasNext()) walk(v.get(keys.next()))
                }
            }
        }
        walk(JSONArray(json))
        return s
    }

    @Test fun latinLayoutHasSymbolsTabAndArrows() {
        val l = labels(load("programming.json"))
        for (need in listOf("{", "}", "[", "]", "(", ")", "<", ">", "/", "\\", "|", "=", ";", "'", "_", "tab", "left", "right", "up", "down"))
            assertTrue("missing $need in programming.json: $l", need in l)
        for (letter in "abcdefghijklmnopqrstuvwxyz")
            assertTrue("missing letter $letter", letter.toString() in l)
    }

    @Test fun russianLayoutHasCyrillicSymbolsAndTab() {
        val l = labels(load("programming_ru.json"))
        for (need in listOf("{", "}", "[", "]", "(", ")", "<", ">", "/", "|", "=", "_", "tab", "left", "right"))
            assertTrue("missing $need in programming_ru.json: $l", need in l)
        for (letter in "йцукенгшщзхфывапролджячсмитьбю")
            assertTrue("missing letter $letter", letter.toString() in l)
    }
}
