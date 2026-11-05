package ru.klaviatura.emoji

import android.content.SharedPreferences
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Settings

/**
 * 1.3.2: one-time migration making emoji reachable on existing installs.
 * - Bottom-row emoji key: default is now true; an explicitly stored value is the user's choice and is kept.
 * - Toolbar: if the user has a stored toolbar list, enable EMOJI and place it right after SETTINGS (once).
 */
object EmojiDefaultsMigration {
    const val PREF_DONE = "klaviatura_emoji_defaults_v1"

    fun run(prefs: SharedPreferences) {
        if (prefs.getBoolean(PREF_DONE, false)) return
        val e = prefs.edit()
        prefs.getString(Settings.PREF_TOOLBAR_KEYS, null)?.let { e.putString(Settings.PREF_TOOLBAR_KEYS, migrateToolbar(it)) }
        e.putBoolean(PREF_DONE, true).apply()
    }

    /** Enable EMOJI and move it after SETTINGS (or to the front when SETTINGS is absent). */
    fun migrateToolbar(value: String): String {
        val entries = value.split(Separators.ENTRY).filter { it.isNotBlank() }.toMutableList()
        entries.removeAll { it.substringBefore(Separators.KV) == "EMOJI" }
        val idx = entries.indexOfFirst { it.substringBefore(Separators.KV) == "SETTINGS" }
        entries.add(idx + 1, "EMOJI" + Separators.KV + "true")
        return entries.joinToString(Separators.ENTRY)
    }
}
