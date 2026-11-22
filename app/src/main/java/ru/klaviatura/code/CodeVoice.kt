// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.code

/**
 * In programming-layout mode, turns spoken symbol/keyword words (Russian and English)
 * into code: «открыть скобку» → "(", «кэмел кейс мой список» → "myList".
 * Pure text transform; works for the built-in engine and for Google voice.
 */
object CodeVoice {
    /** Russian / English words that become identifier tokens (мой → my). */
    internal val GLOSSARY = mapOf(
        "мой" to "my", "моя" to "my", "моё" to "my", "мое" to "my", "моего" to "my",
        "список" to "list", "списка" to "list",
        "имя" to "name", "названия" to "name", "название" to "name",
        "значение" to "value", "значения" to "value",
        "тип" to "type", "файл" to "file", "путь" to "path",
        "пользователь" to "user", "юзер" to "user",
        "данные" to "data", "индекс" to "index",
        "длина" to "length", "размер" to "size", "счетчик" to "count", "счётчик" to "count",
        "строка" to "string", "число" to "number", "функция" to "function",
        "класс" to "class", "объект" to "object", "ошибка" to "error",
        "ключ" to "key", "карта" to "map", "словарь" to "map",
        "элемент" to "item", "элемента" to "item",
        "текст" to "text", "кнопка" to "button", "окно" to "window",
        "запрос" to "request", "ответ" to "response",
        "сервер" to "server", "клиент" to "client",
        "массив" to "array", "буфер" to "buffer",
        "узел" to "node", "дерево" to "tree", "граф" to "graph",
        "правда" to "true", "истина" to "true", "ложь" to "false",
        "нуль" to "null", "ничего" to "null", "пусто" to "null",
        "новый" to "new", "новая" to "new", "это" to "this",
        "возврат" to "return", "верни" to "return",
        "если" to "if", "иначе" to "else", "пока" to "while", "для" to "for",
        "константа" to "const", "конст" to "const", "переменная" to "var", "пусть" to "let",
        "импорт" to "import", "экспорт" to "export", "из" to "from",
        "публичный" to "public", "приватный" to "private", "статический" to "static",
        "интерфейс" to "interface", "пакет" to "package",
        "попробовать" to "try", "поймать" to "catch", "наконец" to "finally",
        "бросить" to "throw", "асинхронный" to "async", "ждать" to "await",
        "переключатель" to "switch", "случай" to "case", "по умолчанию" to "default",
        "получить" to "get", "поставить" to "set", "установить" to "set",
        "добавить" to "add", "удалить" to "remove", "найти" to "find",
        "печатать" to "print", "вывести" to "print", "лог" to "log",
        "главный" to "main", "старт" to "start", "стоп" to "stop",
        "включить" to "enable", "выключить" to "disable",
        "открыть" to "open", "закрыть" to "close", // only as ident tokens, not when used as symbol phrases
        "идти" to "go", "сделать" to "do", "есть" to "is",
        "и" to "and", "или" to "or", "не" to "not",
        "мой" to "my" // duplicate ok
    )

    data class Phrase(val words: List<String>, val out: String)

    /** Longest phrases first. Lowercase, ё normalized by the matcher. */
    internal val PHRASES: List<Phrase> = buildList {
        fun add(src: String, out: String) = add(Phrase(src.lowercase().split(' '), out))
        // multi-word symbols (RU)
        add("открыть фигурную скобку", "{"); add("открывающаяся фигурная скобка", "{")
        add("закрыть фигурную скобку", "}"); add("закрывающаяся фигурная скобка", "}")
        add("открыть квадратную скобку", "["); add("открывающаяся квадратная скобка", "[")
        add("закрыть квадратную скобку", "]"); add("закрывающаяся квадратная скобка", "]")
        add("открыть угловую скобку", "<"); add("закрыть угловую скобку", ">")
        add("открыть скобку", "("); add("открывающаяся скобка", "(")
        add("закрыть скобку", ")"); add("закрывающаяся скобка", ")")
        add("точка с запятой", ";")
        add("новая строка", "\n"); add("перенос строки", "\n"); add("перейти на новую строку", "\n")
        add("двойное равно", "=="); add("равно равно", "==")
        add("тройное равно", "==="); add("строгое равно", "===")
        add("не равно", "!="); add("нестрогое не равно", "!=")
        add("меньше или равно", "<="); add("больше или равно", ">=")
        add("стрелка жирная", "=>"); add("жирная стрелка", "=>"); add("толстая стрелка", "=>")
        add("двойная стрелка", "=>")
        add("обратный слэш", "\\"); add("обратная косая черта", "\\")
        add("косая черта", "/"); 
        add("вертикальная черта", "|"); add("одинарные кавычки", "'")
        add("двойные кавычки", "\""); add("обратные кавычки", "`")
        add("открыть кавычки", "\""); add("закрыть кавычки", "\"")
        // EN multi-word
        add("open curly brace", "{"); add("close curly brace", "}")
        add("open curly bracket", "{"); add("close curly bracket", "}")
        add("open square bracket", "["); add("close square bracket", "]")
        add("open angle bracket", "<"); add("close angle bracket", ">")
        add("open parenthesis", "("); add("close parenthesis", ")")
        add("open paren", "("); add("close paren", ")")
        add("open bracket", "["); add("close bracket", "]")
        add("open brace", "{"); add("close brace", "}")
        add("semicolon", ";"); add("semi colon", ";")
        add("new line", "\n"); add("newline", "\n"); add("line break", "\n")
        add("double equals", "=="); add("equals equals", "==")
        add("triple equals", "==="); add("strict equals", "===")
        add("not equals", "!="); add("not equal", "!=")
        add("less or equal", "<="); add("less than or equal", "<=")
        add("greater or equal", ">="); add("greater than or equal", ">=")
        add("fat arrow", "=>"); add("thick arrow", "=>"); add("double arrow", "=>")
        add("back slash", "\\"); add("backward slash", "\\")
        add("forward slash", "/"); add("vertical bar", "|")
        add("single quote", "'"); add("double quote", "\""); add("back tick", "`"); add("backtick", "`")
        add("question mark", "?"); add("exclamation mark", "!")
        // single tokens RU
        add("скобка", "("); // bare "скобка" → opening, common dictation habit
        add("равно", "="); add("присвоить", "=")
        add("точка", "."); add("запятая", ","); add("двоеточие", ":")
        add("таб", "\t"); add("табуляция", "\t"); add("табы", "\t")
        add("подчёркивание", "_"); add("подчеркивание", "_"); add("нижнее подчёркивание", "_")
        add("дефис", "-"); add("минус", "-"); add("тире", "-")
        add("плюс", "+"); add("звёздочка", "*"); add("звездочка", "*"); add("умножить", "*")
        add("слэш", "/"); add("слеш", "/")
        add("пайп", "|"); add("амперсанд", "&"); add("и знак", "&")
        add("решётка", "#"); add("решетка", "#"); add("хеш", "#"); add("хэш", "#")
        add("собака", "@"); add("эт", "@"); add("э коммерческое", "@")
        add("процент", "%"); add("каретка", "^"); add("крышка", "^")
        add("тильда", "~"); add("волна", "~")
        add("вопросительный знак", "?"); add("восклицательный знак", "!")
        add("кавычка", "\""); add("кавычки", "\"")
        add("апостроф", "'")
        add("меньше", "<"); add("больше", ">")
        add("стрелка", "->")
        add("пробел", " ")
        // single tokens EN
        add("equals", "="); add("equal", "=")
        add("dot", "."); add("period", "."); add("comma", ","); add("colon", ":")
        add("tab", "\t"); add("underscore", "_")
        add("minus", "-"); add("dash", "-"); add("hyphen", "-")
        add("plus", "+"); add("asterisk", "*"); add("star", "*")
        add("slash", "/"); add("pipe", "|"); add("ampersand", "&")
        add("hash", "#"); add("hashtag", "#"); add("pound", "#")
        add("at", "@"); add("at sign", "@")
        add("percent", "%"); add("caret", "^"); add("tilde", "~")
        add("less", "<"); add("greater", ">"); add("arrow", "->")
        add("space", " ")
        add("paren", "("); add("bracket", "["); add("brace", "{")
    }.sortedByDescending { it.words.size }

    private enum class Style { NONE, CAMEL, PASCAL, SNAKE }

    fun maybeRewrite(raw: String, lang: String, code: Boolean = CodeMode.active): String =
        if (code) rewrite(raw, lang) else raw

    fun rewrite(raw: String, lang: String = "ru"): String {
        if (raw.isBlank()) return raw
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        var style = Style.NONE
        val identBuf = ArrayList<String>()
        fun flushIdent() {
            if (identBuf.isEmpty()) return
            val parts = identBuf.toList(); identBuf.clear()
            out.add(when (style) {
                Style.CAMEL -> CodeSwipe.camel(parts)
                Style.PASCAL -> CodeSwipe.pascal(parts)
                Style.SNAKE -> CodeSwipe.snake(parts)
                Style.NONE -> parts.joinToString(" ")
            })
            style = Style.NONE
        }
        fun matchStyle(at: Int): Pair<Style, Int>? {
            fun eq(n: Int, vararg w: String) =
                at + w.size <= tokens.size && tokens.subList(at, at + w.size).map { norm(it) } == w.toList()
            return when {
                eq(2, "кэмел", "кейс") || eq(2, "кемел", "кейс") || eq(2, "camel", "case") ||
                    eq(1, "кэмелкейс") || eq(1, "camelcase") -> Style.CAMEL to 2
                eq(2, "паскаль", "кейс") || eq(2, "пасхал", "кейс") || eq(2, "pascal", "case") ||
                    eq(1, "pascalcase") -> Style.PASCAL to 2
                eq(2, "снейк", "кейс") || eq(2, "змеиный", "кейс") || eq(2, "snake", "case") ||
                    eq(1, "snakecase") || eq(1, "снейккейс") -> Style.SNAKE to 2
                else -> null
            }
        }
        fun matchPhrase(at: Int): Phrase? {
            for (ph in PHRASES) {
                if (at + ph.words.size > tokens.size) continue
                var ok = true
                for (k in ph.words.indices) if (norm(tokens[at + k]) != ph.words[k]) { ok = false; break }
                if (ok) return ph
            }
            for ((spoken, output) in SyntaxPackManager.spokenPhrases().entries.sortedByDescending { it.key.split(Regex("\\s+")).size }) {
                val phraseWords = spoken.split(Regex("\\s+"))
                if (at + phraseWords.size > tokens.size) continue
                if (phraseWords.indices.all { norm(tokens[at + it]) == phraseWords[it] })
                    return Phrase(phraseWords, output)
            }
            return null
        }
        // special: "at sign" is 2 words and is in PHRASES; camel case handled first
        while (i < tokens.size) {
            val st = matchStyle(i)
            if (st != null) {
                flushIdent()
                style = st.first
                // "camel case" is 2 words, "camelcase" is 1 — if second word missing (camelcase), consume 1
                val n = if (i + 1 < tokens.size && norm(tokens[i + 1]) in setOf("кейс", "case")) 2 else 1
                i += n
                continue
            }
            if (style != Style.NONE) {
                val ph = matchPhrase(i)
                if (ph != null) {
                    flushIdent()
                    out.add(ph.out)
                    i += ph.words.size
                    continue
                }
                identBuf.add(tokens[i]); i++; continue
            }
            val ph = matchPhrase(i)
            if (ph != null) {
                out.add(ph.out); i += ph.words.size; continue
            }
            out.add(tokens[i]); i++
        }
        flushIdent()
        return glueSymbols(out)
    }

    /** my, list, мой → identifier token */
    fun toIdentToken(raw: String): String {
        val n = norm(raw)
        GLOSSARY[n]?.let { return it }
        SyntaxPackManager.spokenKeywords()[n]?.let { return it }
        // drop punctuation around a word
        val w = raw.filter { it.isLetterOrDigit() || it == '_' }
        if (w.isEmpty()) return ""
        GLOSSARY[norm(w)]?.let { return it }
        return w
    }

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').trim('"', '\'', ',', '.', '!', '?')

    /** Join symbol tokens without extra spaces, keep spaces around identifiers. */
    internal fun glueSymbols(parts: List<String>): String {
        if (parts.isEmpty()) return ""
        val sb = StringBuilder()
        fun isSym(s: String) = s.isNotEmpty() && s.all { !it.isLetterOrDigit() } || s == "\n" || s == "\t"
        for (p in parts) {
            if (sb.isEmpty()) { sb.append(p); continue }
            if (p == "\n" || sb.endsWith("\n") || p == "\t" || sb.endsWith("\t")) {
                sb.append(p); continue
            }
            val prev = sb.last()
            if (isSym(p) || (!prev.isLetterOrDigit() && prev != '_' && p.first().isLetterOrDigit().not())) {
                sb.append(p)
            } else if (!prev.isLetterOrDigit() && prev != '_' && prev != ' ') {
                sb.append(p)
            } else {
                sb.append(' ').append(p)
            }
        }
        return sb.toString().replace(Regex(" +"), " ").replace(" \n", "\n").replace("\n ", "\n").trim()
    }
}
