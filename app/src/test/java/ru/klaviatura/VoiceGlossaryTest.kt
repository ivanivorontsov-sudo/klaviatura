// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.klaviatura.voice.VoiceGlossary

class VoiceGlossaryTest {
    @Test fun expandsSpokenTechnicalInitialisms() {
        assertEquals(listOf("РКН", "VPN", "БМП", "БЦЖ"),
            VoiceGlossary.normalize(listOf("эр", "ка", "эн", "ви", "пи", "эн", "бэ", "эм", "пэ", "бэ", "цэ", "жэ")))
    }

    @Test fun leavesOrdinaryWordsAlone() {
        assertEquals(listOf("мы", "пошли", "домой"), VoiceGlossary.normalize(listOf("мы", "пошли", "домой")))
    }
}
