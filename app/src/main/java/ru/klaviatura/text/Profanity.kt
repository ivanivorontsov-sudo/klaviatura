// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.text

import android.content.Context
import kotlin.concurrent.thread

/**
 * «Блокировать нецензурные и грубые слова» (1.3.1, on by default).
 * Word lists (assets/glide/blocked_ru.txt, blocked_en.txt) are generated from the lexicon with curated root
 * patterns + a whitelist (so «пока», «употребить», «ребёнок», «команда» stay allowed), see ci/README note.
 * When on: such words never appear in swipe candidates, suggestions, next-word predictions or autocorrect,
 * are not learned from typing, and voice input masks them (х**).
 */
object Profanity {
    const val PREF = "kl_block_profanity"
    @Volatile var words: Set<String> = emptySet()
    @Volatile private var ctx: Context? = null
    /** cached pref value, refreshed by [refresh] (onStartInputView) and the settings switch */
    @Volatile var enabled = true

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        refresh()
        thread(name = "kl-profanity") {
            val s = HashSet<String>()
            for (l in listOf("ru", "en")) try {
                ctx!!.assets.open("glide/blocked_$l.txt").bufferedReader().useLines { ls -> ls.forEach { if (it.isNotBlank()) s.add(it.trim()) } }
            } catch (e: Throwable) { }
            words = s
        }
    }

    fun refresh() {
        val c = ctx ?: return
        enabled = try { helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(c).getBoolean(PREF, true) } catch (e: Throwable) { true }
    }

    fun isBad(word: String): Boolean {
        val w = word.lowercase().trim()
        return w in words || w.replace('ё', 'е') in words
    }

    fun blocks(word: String): Boolean = enabled && isBad(word)

    /** voice: «хрен» stays, blocked words become first letter + asterisks */
    fun mask(word: String): String {
        if (!blocks(word.trimEnd(',', '.', '!', '?', ':', ';'))) return word
        val core = word.trimEnd(',', '.', '!', '?', ':', ';'); val tail = word.substring(core.length)
        return core.take(1) + "*".repeat((core.length - 1).coerceAtLeast(1)) + tail
    }
}
