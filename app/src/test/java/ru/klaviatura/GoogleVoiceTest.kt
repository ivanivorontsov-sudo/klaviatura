package ru.klaviatura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.klaviatura.voice.GoogleVoice
import ru.klaviatura.voice.VoiceInput

class GoogleVoiceTest {
    @Test fun defaultEngineIsBuiltin() {
        assertEquals(VoiceInput.ENGINE_BUILTIN, VoiceInput.resolveEngine(null, true))
        assertEquals(VoiceInput.ENGINE_BUILTIN, VoiceInput.resolveEngine("", true))
    }

    @Test fun migratesOldBooleanOffToGoogle() {
        assertEquals(VoiceInput.ENGINE_GOOGLE, VoiceInput.resolveEngine(null, false))
    }

    @Test fun newPrefWins() {
        assertEquals(VoiceInput.ENGINE_GOOGLE, VoiceInput.resolveEngine(VoiceInput.ENGINE_GOOGLE, true))
        assertEquals(VoiceInput.ENGINE_BUILTIN, VoiceInput.resolveEngine(VoiceInput.ENGINE_BUILTIN, false))
    }

    @Test fun googleImeIdsAreRecognized() {
        assertTrue(GoogleVoice.isGoogleImeId("com.google.android.googlequicksearchbox/com.google.android.voicesearch.ime.VoiceInputMethodService"))
        assertTrue(GoogleVoice.isGooglePackage("com.google.android.googlequicksearchbox"))
        assertFalse(GoogleVoice.isGoogleImeId("ru.ivanivorontsov.klaviatura/.latin.LatinIME"))
        assertFalse(GoogleVoice.isGooglePackage("ru.ivanivorontsov.klaviatura"))
    }
}
