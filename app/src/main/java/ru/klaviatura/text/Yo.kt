// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.text

import android.content.Context
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import kotlin.concurrent.thread

/**
 * «Писать ё» (1.3.1, on by default). ru_yo.txt maps е-spellings that are not words of their own (ещё, её, чёрный,
 * ёлка...) to the ё-form (OpenCorpora). Ambiguous pairs (все/всё, небо/нёбо) are not in the map: the lexicon keeps
 * both and the context decides. With the option off every ё is written as е.
 */
object Yo {
    const val PREF = "kl_write_yo"
    @Volatile var map: Map<String, String> = emptyMap()
    @Volatile var enabled = true
    @Volatile private var ctx: Context? = null

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext; refresh()
        thread(name = "kl-yo") {
            try { map = ctx!!.assets.open("glide/ru_yo.txt").bufferedReader().useLines { parse(it) } } catch (e: Throwable) { }
        }
    }

    fun parse(lines: Sequence<String>): Map<String, String> {
        val m = HashMap<String, String>()
        for (l in lines) { val t = l.indexOf('\t'); if (t > 0) m[l.substring(0, t)] = l.substring(t + 1).trim() }
        return m
    }

    fun refresh() {
        val c = ctx ?: return
        enabled = try { helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(c).getBoolean(PREF, true) } catch (e: Throwable) { true }
    }

    /** keeps the case pattern of [word] (Еще -> Ещё, ЕЩЕ -> ЕЩЁ) and trailing punctuation */
    fun fix(word: String): String {
        if (!enabled) return if (word.contains('ё') || word.contains('Ё')) word.replace('ё', 'е').replace('Ё', 'Е') else word
        if (!word.contains('е') && !word.contains('Е')) return word
        val core = word.trimEnd { !it.isLetter() }; val tail = word.substring(core.length)
        val y = map[core.lowercase()] ?: return word
        val sb = StringBuilder(core.length)
        for (i in core.indices) { val c = y.getOrElse(i) { core[i] }; sb.append(if (core[i].isUpperCase()) c.uppercaseChar() else c) }
        return sb.toString() + tail
    }

    fun info(i: SuggestedWordInfo): SuggestedWordInfo {
        if (i.mApplicationSpecifiedCompletionInfo != null) return i
        val w = fix(i.mWord); if (w == i.mWord) return i
        return SuggestedWordInfo(w, i.mPrevWordsContext, i.mScore, i.mKindAndFlags, i.mSourceDict, i.mIndexOfTouchPointOfSecondWord, i.mAutoCommitFirstWordConfidence)
    }
}
