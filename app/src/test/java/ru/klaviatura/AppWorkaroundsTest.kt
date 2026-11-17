// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura

import android.text.InputType
import helium314.keyboard.compat.AppWorkarounds
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppWorkaroundsTest {
    private val noSuggestions = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

    @Test
    fun `NO_SUGGESTIONS is ignored for every ordinary text variation`() {
        val variations = listOf(
            0,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        )
        variations.forEach { variation ->
            val inputType = InputType.TYPE_CLASS_TEXT or variation or noSuggestions
            assertTrue("variation=0x${variation.toString(16)}", AppWorkarounds.shouldShowSuggestions(inputType))
        }
    }

    @Test
    fun `password fields never show dictionary suggestions`() {
        val passwordVariations = listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )
        passwordVariations.forEach { variation ->
            val inputType = InputType.TYPE_CLASS_TEXT or variation or noSuggestions
            assertFalse("variation=0x${variation.toString(16)}", AppWorkarounds.shouldShowSuggestions(inputType))
        }
        assertFalse(AppWorkarounds.shouldShowSuggestions(
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD or noSuggestions
        ))
    }

    @Test
    fun `non-text fields do not show word suggestions`() {
        assertFalse(AppWorkarounds.shouldShowSuggestions(InputType.TYPE_CLASS_NUMBER))
        assertFalse(AppWorkarounds.shouldShowSuggestions(InputType.TYPE_CLASS_PHONE))
        assertFalse(AppWorkarounds.shouldShowSuggestions(InputType.TYPE_CLASS_DATETIME))
    }
}
