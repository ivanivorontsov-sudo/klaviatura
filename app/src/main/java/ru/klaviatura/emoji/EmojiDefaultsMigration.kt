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

    const val PREF_PINNED_DONE = "klaviatura_pinned_defaults_v2"

    fun run(prefs: SharedPreferences) {
        runPinned(prefs)
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

    /** 1.4.0: clipboard, emoji and mic pinned on the suggestion strip (once; the user may unpin them later) */
    fun runPinned(prefs: SharedPreferences) {
        if (prefs.getBoolean(PREF_PINNED_DONE, false)) return
        val e = prefs.edit()
        prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, null)?.let { e.putString(Settings.PREF_PINNED_TOOLBAR_KEYS, migratePinned(it)) }
        e.putBoolean(PREF_PINNED_DONE, true).apply()
    }

    fun migratePinned(value: String, keys: List<String> = listOf("CLIPBOARD", "EMOJI", "VOICE")): String {
        val entries = value.split(Separators.ENTRY).filter { it.isNotBlank() }.toMutableList()
        entries.removeAll { it.substringBefore(Separators.KV) in keys }
        return (keys.map { it + Separators.KV + "true" } + entries).joinToString(Separators.ENTRY)
    }
}
