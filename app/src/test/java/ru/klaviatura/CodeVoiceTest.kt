package ru.klaviatura

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.klaviatura.code.CodeVoice
import ru.klaviatura.code.CodeSwipe
import ru.klaviatura.glide.Lexicon

class CodeVoiceTest {
    @Test fun russianSymbols() {
        assertEquals("(", CodeVoice.rewrite("открыть скобку", "ru"))
        assertEquals("=", CodeVoice.rewrite("равно", "ru"))
        assertEquals(";", CodeVoice.rewrite("точка с запятой", "ru"))
        assertEquals("if()", CodeVoice.rewrite("if открыть скобку закрыть скобку", "ru").replace(" ", ""))
        assertEquals("x=1;", CodeVoice.rewrite("x равно 1 точка с запятой", "ru").replace(" ", ""))
    }

    @Test fun newLineAndTab() {
        assertEquals("a\nb", CodeVoice.rewrite("a новая строка b", "ru"))
        assertEquals("a\nb", CodeVoice.rewrite("a new line b", "en"))
        assertEquals("a\tb", CodeVoice.rewrite("a таб b", "ru"))
    }

    @Test fun camelCaseMyList() {
        assertEquals("myList", CodeVoice.rewrite("кэмел кейс мой список", "ru"))
        assertEquals("myList", CodeVoice.rewrite("camel case my list", "en"))
        assertEquals("my_list", CodeVoice.rewrite("снейк кейс мой список", "ru"))
        assertEquals("MyList", CodeVoice.rewrite("паскаль кейс мой список", "ru"))
    }

    @Test fun englishSymbols() {
        assertEquals("{", CodeVoice.rewrite("open curly brace", "en"))
        assertEquals("==", CodeVoice.rewrite("double equals", "en"))
        assertEquals("->", CodeVoice.rewrite("arrow", "en"))
        assertEquals("=>", CodeVoice.rewrite("fat arrow", "en"))
    }

    @Test fun joinSwipeParts() {
        val j = CodeSwipe.joinWithPrevious("my", "list")
        assertEquals("myList", j[0])
        assertEquals("my_list", j[1])
    }

    @Test fun splitCompound() {
        val lex = Lexicon("code")
        listOf("my", "list", "get", "name", "user", "id").forEach { lex.add(it, 1000) }
        val r = CodeSwipe.splitCompound("mylist", lex)
        assertEquals(true, r.contains("myList"))
        assertEquals(true, r.contains("my_list"))
    }
}
