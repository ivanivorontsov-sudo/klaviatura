// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.code

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
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
 * Optional Russian and Latin programming layouts. They are added only after an explicit opt-in
 * in Settings; the toolbar action merely switches to an already enabled layout.
 */
object CodeLayoutSwitch {
    private const val PREF_MIGRATION_V2 = "klaviatura_code_layout_v2"
    const val PREF_CODE_LAYOUTS_ENABLED = "klaviatura_code_layouts_enabled"
    private var previous: InputMethodSubtype? = null

    fun isEnabled(context: Context): Boolean =
        context.prefs().getBoolean(PREF_CODE_LAYOUTS_ENABLED, false)

    /** pure decision: main layout to go to, null = leave the code layout */
    fun targetLayout(currentLayout: String?, cyrillic: Boolean): String? = when {
        CodeMode.isCodeLayout(currentLayout) -> null
        cyrillic -> CodeMode.LAYOUT_RU
        else -> CodeMode.LAYOUT_LATIN
    }

    /** Keep the CODE action enabled, but move it out of the pinned row and into the expandable menu. */
    fun unpinCodeKey(value: String): String {
        val entries = value.split(Separators.ENTRY).filter { it.isNotBlank() }.toMutableList()
        val index = entries.indexOfFirst { it.substringBefore(Separators.KV) == "CODE" }
        if (index >= 0) entries[index] = "CODE" + Separators.KV + "false"
        else entries.add("CODE" + Separators.KV + "false")
        return entries.joinToString(Separators.ENTRY)
    }

    /** layouts to enable on update: always Latin «Code», plus the Cyrillic «Код» when a Cyrillic language is enabled */
    fun layoutsToEnable(enabledLayouts: Collection<String?>, hasCyrillic: Boolean): List<String> {
        val wanted = if (hasCyrillic) listOf(CodeMode.LAYOUT_LATIN, CodeMode.LAYOUT_RU) else listOf(CodeMode.LAYOUT_LATIN)
        return wanted.filter { it !in enabledLayouts }
    }

    /**
     * One-time migration: preserve programming layouts that an existing user already has,
     * but do not add them automatically. Move the CODE action from the pinned row to the
     * expandable toolbar so the suggestion strip keeps its space.
     */
    fun migrate(prefs: SharedPreferences) {
        if (prefs.getBoolean(PREF_MIGRATION_V2, false)) return
        try {
            if (!prefs.contains(PREF_CODE_LAYOUTS_ENABLED)) {
                val alreadyEnabled = SubtypeSettings.getEnabledSubtypes(true)
                    .any { CodeMode.isCodeLayout(it.mainLayoutName()) }
                prefs.edit().putBoolean(PREF_CODE_LAYOUTS_ENABLED, alreadyEnabled).apply()
            }
            prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, null)?.let {
                prefs.edit().putString(Settings.PREF_PINNED_TOOLBAR_KEYS, unpinCodeKey(it)).apply()
            }
        } catch (e: Throwable) {
            android.util.Log.w("KlCode", "code layout migration", e)
        }
        prefs.edit().putBoolean(PREF_MIGRATION_V2, true).apply()
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

    /** Called only from the explicit Settings switch. */
    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.prefs()
        if (enabled) {
            enableLayouts(prefs)
        } else {
            SubtypeSettings.getEnabledSubtypes(true)
                .filter { CodeMode.isCodeLayout(it.mainLayoutName()) }
                .forEach { SubtypeSettings.removeEnabledSubtype(context, it) }
        }
        prefs.edit().putBoolean(PREF_CODE_LAYOUTS_ENABLED, enabled).apply()
    }

    /** the «</>» action in the expandable toolbar: into the code layout and back. */
    fun toggle(ime: LatinIME) {
        val prefs = ime.prefs()
        if (!prefs.getBoolean(PREF_CODE_LAYOUTS_ENABLED, false)) {
            Toast.makeText(ime, "Сначала включите программные раскладки в настройках", Toast.LENGTH_LONG).show()
            return
        }
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
