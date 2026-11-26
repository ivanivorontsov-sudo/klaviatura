package ru.klaviatura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.defaultPinnedToolbarPref
import helium314.keyboard.latin.utils.defaultToolbarPref
import ru.klaviatura.code.CodeLayoutSwitch
import ru.klaviatura.code.CodeMode

/** Programming layouts are opt-in; their shortcut lives in the expandable toolbar. */
class CodeLayoutTest {
    @Test fun toggleGoesIntoCodeAndBack() {
        assertEquals(CodeMode.LAYOUT_RU, CodeLayoutSwitch.targetLayout("ru", true))
        assertEquals(CodeMode.LAYOUT_LATIN, CodeLayoutSwitch.targetLayout("qwerty", false))
        assertEquals(CodeMode.LAYOUT_LATIN, CodeLayoutSwitch.targetLayout(null, false))
        assertNull(CodeLayoutSwitch.targetLayout(CodeMode.LAYOUT_LATIN, false))
        assertNull(CodeLayoutSwitch.targetLayout(CodeMode.LAYOUT_RU, true))
    }

    @Test fun layoutsToEnablePreservesTheUsersCurrentLanguages() {
        assertEquals(listOf("programming", "programming_ru"), CodeLayoutSwitch.layoutsToEnable(listOf("ru", "qwerty"), true))
        assertEquals(listOf("programming"), CodeLayoutSwitch.layoutsToEnable(listOf(null), false))
        assertTrue(CodeLayoutSwitch.layoutsToEnable(listOf("ru", "programming", "programming_ru"), true).isEmpty())
    }

    @Test fun codeKeyIsUnpinnedWithoutDroppingOtherKeys() {
        val sep = Separators.ENTRY
        val kv = Separators.KV
        val old = listOf("CLIPBOARD${kv}true", "EMOJI${kv}true", "VOICE${kv}true", "CODE${kv}true", "UNDO${kv}false").joinToString(sep)
        val new = CodeLayoutSwitch.unpinCodeKey(old).split(sep)
        assertEquals(listOf("CLIPBOARD${kv}true", "EMOJI${kv}true", "VOICE${kv}true", "CODE${kv}false", "UNDO${kv}false"), new)
        assertEquals(new, CodeLayoutSwitch.unpinCodeKey(new.joinToString(sep)).split(sep))
    }

    @Test fun codeKeyIsInExpandableToolbarButNotPinnedByDefault() {
        assertTrue(ToolbarKey.entries.any { it.name == "CODE" })
        assertTrue(defaultToolbarPref.split(Separators.ENTRY).contains("CODE${Separators.KV}true"))
        assertTrue(defaultPinnedToolbarPref.split(Separators.ENTRY).contains("CODE${Separators.KV}false"))
    }
}
