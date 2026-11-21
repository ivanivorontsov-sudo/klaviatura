// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.code

import android.content.SharedPreferences
import android.view.inputmethod.InputMethodSubtype
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.ScriptUtils.script
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.mainLayoutName
import helium314.keyboard.latin.utils.prefs

/**
 * 1.5.9: the programming layout was invisible because it only existed as an optional layout that had to be added by
 * hand in the language settings. Now it is (a) enabled for existing users on update, so it shows up in the globe /
 * space-long-press language switch, and (b) reachable with the «</>» toolbar key, which toggles it.
 */
object CodeLayoutSwitch {
    const val PREF_DONE = "klaviatura_code_layout_v1"
    private var previous: InputMethodSubtype? = null

    /** pure decision: main layout to go to, null = leave the code layout */
    fun targetLayout(currentLayout: String?, cyrillic: Boolean): String? = when {
        CodeMode.isCodeLayout(currentLayout) -> null
        cyrillic -> CodeMode.LAYOUT_RU
        else -> CodeMode.LAYOUT_LATIN
    }

    /** pure: put CODE on, right after the last pinned key that is on (other pinned keys stay as they are) */
    fun pinCodeKey(value: String): String {
        val entries = value.split(Separators.ENTRY).filter { it.isNotBlank() }.toMutableList()
        entries.removeAll { it.substringBefore(Separators.KV) == "CODE" }
        val last = entries.indexOfLast { it.substringAfter(Separators.KV, "") == "true" }
        entries.add(last + 1, "CODE" + Separators.KV + "true")
        return entries.joinToString(Separators.ENTRY)
    }

    /** layouts to enable on update: always Latin «Code», plus the Cyrillic «Код» when a Cyrillic language is enabled */
    fun layoutsToEnable(enabledLayouts: Collection<String?>, hasCyrillic: Boolean): List<String> {
        val wanted = if (hasCyrillic) listOf(CodeMode.LAYOUT_LATIN, CodeMode.LAYOUT_RU) else listOf(CodeMode.LAYOUT_LATIN)
        return wanted.filter { it !in enabledLayouts }
    }

    /** one-time, on update: enable the layouts (never removing the user's own) and pin the «</>» key */
    fun migrate(prefs: SharedPreferences) {
        if (prefs.getBoolean(PREF_DONE, false)) return
        try {
            enableLayouts(prefs)
            prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, null)?.let {
                prefs.edit().putString(Settings.PREF_PINNED_TOOLBAR_KEYS, pinCodeKey(it)).apply()
            }
            prefs.getString(Settings.PREF_TOOLBAR_KEYS, null)?.let {
                prefs.edit().putString(Settings.PREF_TOOLBAR_KEYS, pinCodeKey(it)).apply()
            }
        } catch (e: Throwable) {
            android.util.Log.w("KlCode", "code layout migration", e)
        }
        prefs.edit().putBoolean(PREF_DONE, true).apply()
    }

    private fun enableLayouts(prefs: SharedPreferences) {
        val enabledNow = SubtypeSettings.getEnabledSubtypes(true).toList()
        val hasCyr = enabledNow.any { it.locale().script() == ScriptUtils.SCRIPT_CYRILLIC }
        val toAdd = layoutsToEnable(enabledNow.map { it.mainLayoutName() }, hasCyr)
        if (toAdd.isEmpty()) return
        // an empty list means "system languages": write them out first, so adding a layout does not replace them
        val explicit = prefs.getString(Settings.PREF_ENABLED_SUBTYPES, "").orEmpty().isNotBlank()
        if (!explicit) enabledNow.forEach { SubtypeSettings.addEnabledSubtype(prefs, it) }
        for (layout in toAdd) findOrAdd(prefs, layout)
    }

    private fun findOrAdd(prefs: SharedPreferences, layout: String): InputMethodSubtype? {
        SubtypeSettings.getEnabledSubtypes(true).firstOrNull { it.mainLayoutName() == layout }?.let { return it }
        val s = SubtypeSettings.getAllAvailableSubtypes().firstOrNull { it.mainLayoutName() == layout } ?: return null
        SubtypeSettings.addEnabledSubtype(prefs, s)
        return s
    }

    /** the «</>» toolbar key: into the code layout and back to the previous layout */
    fun toggle(ime: LatinIME) {
        val rim = RichInputMethodManager.getInstance()
        val cur = rim.currentSubtype
        val cyr = rim.currentSubtypeLocale.script() == ScriptUtils.SCRIPT_CYRILLIC
        val target = targetLayout(cur.mainLayoutName, cyr)
        val prefs = ime.prefs()
        if (target != null) {
            val s = findOrAdd(prefs, target) ?: findOrAdd(prefs, CodeMode.LAYOUT_LATIN) ?: return
            previous = cur.rawSubtype
            ime.switchToSubtype(s)
        } else {
            val enabled = SubtypeSettings.getEnabledSubtypes(true).filter { !CodeMode.isCodeLayout(it.mainLayoutName()) }
            val back = previous?.takeIf { it in enabled }
                ?: enabled.firstOrNull { (it.locale().script() == ScriptUtils.SCRIPT_CYRILLIC) == (cur.mainLayoutName == CodeMode.LAYOUT_RU) }
                ?: enabled.firstOrNull() ?: return
            ime.switchToSubtype(back)
        }
    }
}
