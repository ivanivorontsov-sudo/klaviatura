// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.glide

import java.text.Normalizer

/**
 * Letters that have no own key on the visible layout are swiped over their base key:
 * ą→a, ł→l, ß→s, ü→u (when there is no ü key), ё→е, ъ→ь, ґ→г, ...
 */
object Fold {
    private val special = mapOf('ł' to 'l', 'ß' to 's', 'ø' to 'o', 'æ' to 'a', 'œ' to 'o', 'đ' to 'd', 'ð' to 'd', 'þ' to 't',
        'ı' to 'i', 'ё' to 'е', 'ъ' to 'ь', 'ґ' to 'г', 'ї' to 'і', 'й' to 'й', 'ŀ' to 'l', 'ħ' to 'h')
    private val cache = java.util.concurrent.ConcurrentHashMap<Char, Char>()

    fun fold(c: Char): Char = cache.getOrPut(c) {
        special[c] ?: run {
            val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            if (d.isNotEmpty() && d[0] != c && d[0].isLetter()) d[0] else c
        }
    }

    /** hyphens and apostrophes are not swiped (where-ever they are, the word is decoded from its letters) */
    fun isJoiner(c: Char) = c == '-' || c == '\'' || c == '’'
}
