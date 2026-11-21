// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.code

import helium314.keyboard.keyboard.Keyboard

/** True when the visible main layout is one of the programming layouts. */
object CodeMode {
    const val LAYOUT_LATIN = "programming"
    const val LAYOUT_RU = "programming_ru"
    const val LEX_LANG = "code"

    /** updated when the IME shows a keyboard */
    @Volatile var active = false

    fun isCodeLayout(name: String?): Boolean =
        name == LAYOUT_LATIN || name == LAYOUT_RU

    fun isCodeLayout(keyboard: Keyboard): Boolean =
        isCodeLayout(keyboard.mId.subtype.mainLayoutName)

    fun updateFrom(keyboard: Keyboard?) {
        active = keyboard != null && isCodeLayout(keyboard)
    }
}
