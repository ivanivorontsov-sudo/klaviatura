package ru.klaviatura

import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.utils.defaultToolbarPref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.klaviatura.emoji.EmojiDefaultsMigration

/** 1.3.2: emoji reachable by default */
class EmojiDefaultsTest {
    @Test fun bottomRowEmojiKeyOnByDefault() = assertTrue(Defaults.PREF_SHOW_EMOJI_KEY)

    @Test fun toolbarDefaultHasEmojiAfterSettings() =
        assertTrue(defaultToolbarPref, defaultToolbarPref.startsWith("SETTINGS:true|EMOJI:true|"))

    @Test fun migrationEnablesAndMovesEmoji() {
        val old = "SETTINGS:true|VOICE:true|CLIPBOARD:true|EMOJI:false|COPY:true"
        assertEquals("SETTINGS:true|EMOJI:true|VOICE:true|CLIPBOARD:true|COPY:true", EmojiDefaultsMigration.migrateToolbar(old))
    }

    @Test fun migrationWithoutSettingsPutsEmojiFirst() =
        assertEquals("EMOJI:true|VOICE:true", EmojiDefaultsMigration.migrateToolbar("VOICE:true|EMOJI:false"))

    @Test fun migrationIsIdempotent() {
        val once = EmojiDefaultsMigration.migrateToolbar("SETTINGS:false|EMOJI:false|UNDO:true")
        assertEquals(once, EmojiDefaultsMigration.migrateToolbar(once))
    }
}
