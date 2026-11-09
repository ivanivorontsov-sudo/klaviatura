// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.klaviatura.voice.VoiceInput
import ru.klaviatura.voice.silenceMillis

class VoiceSilenceTest {
    @Test fun silenceTimeout() {
        assertEquals(20_000L, silenceMillis(VoiceInput.DEFAULT_SILENCE_S).toLong())
        assertTrue("short pauses never stop", silenceMillis(5).toLong() > VoiceInput.PHRASE_PAUSE_S * 1000)
        assertEquals(Long.MAX_VALUE, silenceMillis(VoiceInput.SILENCE_NEVER).toLong())
    }
}
