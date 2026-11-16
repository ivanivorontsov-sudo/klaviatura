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
    fun `Vivaldi address bar may show dictionary candidates`() {
        val uriField = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or noSuggestions
        assertTrue(AppWorkarounds.mayOverrideNoSuggestionsFlag(uriField, "com.vivaldi.browser"))
    }

    @Test
    fun `Vivaldi web page fields keep their original suggestion policy`() {
        val pageField = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT or noSuggestions
        assertFalse(AppWorkarounds.mayOverrideNoSuggestionsFlag(pageField, "com.vivaldi.browser"))
    }

    @Test
    fun `Gmail and Gemini prose fields may show candidates`() {
        val normalText = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or noSuggestions
        assertTrue(AppWorkarounds.mayOverrideNoSuggestionsFlag(normalText, "com.google.android.gm"))
        assertTrue(AppWorkarounds.mayOverrideNoSuggestionsFlag(normalText, "com.google.android.apps.bard"))
    }

    @Test
    fun `Google app hosted assistant text fields may show candidates`() {
        val assistantPrompt = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or noSuggestions
        assertTrue(AppWorkarounds.mayOverrideNoSuggestionsFlag(assistantPrompt, "com.google.android.googlequicksearchbox"))
    }

    @Test
    fun `Google app URI fields remain excluded from app-specific override`() {
        val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or noSuggestions
        assertFalse(AppWorkarounds.mayOverrideNoSuggestionsFlag(uri, "com.google.android.googlequicksearchbox"))
    }

    @Test
    fun `email fields remain excluded from app-specific override`() {
        val email = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or noSuggestions
        assertFalse(AppWorkarounds.mayOverrideNoSuggestionsFlag(email, "com.google.android.gm"))
        assertFalse(AppWorkarounds.mayOverrideNoSuggestionsFlag(email, "com.google.android.apps.bard"))
    }

    @Test
    fun `password fields are never overridden`() {
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or noSuggestions
        assertFalse(AppWorkarounds.mayOverrideNoSuggestionsFlag(password, "com.google.android.gm"))
        assertFalse(AppWorkarounds.mayOverrideNoSuggestionsFlag(password, "com.google.android.apps.bard"))
    }

    @Test
    fun `unrelated apps keep Android editor flags`() {
        val normalText = InputType.TYPE_CLASS_TEXT or noSuggestions
        assertFalse(AppWorkarounds.mayOverrideNoSuggestionsFlag(normalText, "com.example.app"))
    }
}
