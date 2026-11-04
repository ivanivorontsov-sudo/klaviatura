// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.emoji

import android.content.Context
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Word → emoji suggestions (кот → 🐱). Data: Unicode CLDR annotations (ru, en; Unicode license) with all Russian
 * inflected forms (pymorphy3/OpenCorpora) plus a small hand-made list of everyday words. Loaded lazily off the UI thread.
 */
object EmojiSuggest {
    const val PREF = "kl_emoji_suggest"
    private val maps = ConcurrentHashMap<String, Map<String, List<String>>>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var ctx: Context? = null

    fun init(context: Context) { ctx = context.applicationContext }

    fun parse(lines: Sequence<String>): Map<String, List<String>> {
        val m = HashMap<String, List<String>>()
        for (l in lines) { val t = l.indexOf('\t'); if (t > 0) m[l.substring(0, t)] = l.substring(t + 1).split(' ') }
        return m
    }

    private fun map(lang: String): Map<String, List<String>>? {
        maps[lang]?.let { return it }
        val c = ctx ?: return null
        if (loading.add(lang)) thread(name = "kl-emoji-$lang") {
            try { maps[lang] = c.assets.open("emoji/${lang}_emoji.tsv").bufferedReader().useLines { parse(it) } }
            catch (e: Throwable) { maps[lang] = emptyMap() }
        }
        return null
    }

    fun lookup(word: String, langs: List<String>, data: (String) -> Map<String, List<String>>? = ::map): List<String> {
        val w = word.lowercase().trim()
        if (w.length < 2) return emptyList()
        val out = LinkedHashSet<String>()
        for (l in langs) data(l)?.get(w)?.let { out.addAll(it) }
        return out.take(2)
    }

    fun enabled(): Boolean = ctx?.let { helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(it).getBoolean(PREF, true) } ?: false

    /** emoji infos scored just below the current 2nd suggestion, so they show in the strip but never autocorrect */
    fun infos(word: String, langs: List<String>, scores: List<Int>): List<SuggestedWordInfo> {
        if (!enabled()) return emptyList()
        val em = lookup(word, langs)
        if (em.isEmpty()) return emptyList()
        val base = (scores.sortedDescending().getOrNull(1) ?: scores.firstOrNull() ?: 1) - 1
        return em.mapIndexed { i, e ->
            SuggestedWordInfo(e, "", base - i, SuggestedWordInfo.KIND_COMPLETION, Dictionary.DICTIONARY_USER_TYPED,
                SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
        }
    }
}
