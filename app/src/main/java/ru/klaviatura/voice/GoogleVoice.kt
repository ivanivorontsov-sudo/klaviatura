// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.inputmethod.InputMethodManager
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.InputMethodSubtype
import android.widget.Toast
import helium314.keyboard.latin.R
import java.util.Locale

/**
 * System Google voice typing: prefer switching to Google's voice IME,
 * otherwise SpeechRecognizer / RecognizerIntent with the Google service.
 * If Google is not installed, the caller shows a toast and does nothing else.
 */
object GoogleVoice {
    const val PKG = "com.google.android.googlequicksearchbox"
    const val GMS = "com.google.android.gms"
    const val VOICE_IME = "com.google.android.voicesearch.ime.VoiceInputMethodService"
    const val RECOGNIZER = "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"

    /** Pure id check, unit-tested without a device. */
    fun isGoogleImeId(id: String): Boolean {
        val s = id.lowercase(Locale.US)
        return "googlequicksearchbox" in s || "voicesearch.ime" in s ||
            (s.contains("com.google.android") && s.contains("voice"))
    }

    fun isGooglePackage(pkg: String): Boolean {
        val s = pkg.lowercase(Locale.US)
        return s == PKG || s == GMS || s.startsWith("com.google.android.googlequicksearchbox")
    }

    fun isAppInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (_: Exception) {
        try {
            context.packageManager.getPackageInfo(GMS, 0)
            true
        } catch (_: Exception) { false }
    }

    data class ImeTarget(val id: String, val subtype: InputMethodSubtype?)

    fun findVoiceIme(imm: InputMethodManager): ImeTarget? {
        val enabled = try { imm.enabledInputMethodList } catch (_: Exception) { emptyList() }
        val all = try { imm.inputMethodList } catch (_: Exception) { emptyList() }
        for (list in listOf(enabled, all)) {
            for (imi in list) {
                if (!isGooglePackage(imi.packageName) && !isGoogleImeId(imi.id)) continue
                val subtypes = try { imm.getEnabledInputMethodSubtypeList(imi, true) } catch (_: Exception) { emptyList() }
                val voice = subtypes.firstOrNull { sub ->
                    val mode = try { sub.mode } catch (_: Exception) { "" }
                    mode.equals("voice", true) || (sub.extraValue?.contains("voice", true) == true)
                }
                if (voice != null || imi.serviceName?.contains("Voice", true) == true || isGoogleImeId(imi.id)) {
                    return ImeTarget(imi.id, voice ?: subtypes.firstOrNull())
                }
            }
        }
        return null
    }

    fun findRecognitionService(pm: PackageManager): ComponentName? {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val list = if (Build.VERSION.SDK_INT >= 33)
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        val google = list.firstOrNull { isGooglePackage(it.activityInfo.packageName) }
        if (google != null) return ComponentName(google.activityInfo.packageName, google.activityInfo.name)
        // well-known recognition service
        return try {
            pm.getServiceInfo(ComponentName(PKG, RECOGNIZER), 0)
            ComponentName(PKG, RECOGNIZER)
        } catch (_: Exception) { null }
    }

    /**
     * Switch the current IME to Google voice typing.
     * @return true if the switch was requested, false if Google voice IME is not available.
     */
    fun switchTo(ims: InputMethodService): Boolean {
        val imm = ims.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        val target = findVoiceIme(imm) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ims.switchInputMethod(target.id, target.subtype)
            } else {
                val token = ims.window.window?.attributes?.token ?: return false
                @Suppress("DEPRECATION")
                imm.setInputMethodAndSubtype(token, target.id, target.subtype)
            }
            true
        } catch (e: Exception) {
            android.util.Log.w("KlVoice", "switch to Google voice IME", e)
            false
        }
    }

    private var recognizer: SpeechRecognizer? = null

    fun stopRecognizer() {
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    /**
     * Start Google SpeechRecognizer and pipe results into [target].
     * @return false if Google recognition is not available.
     */
    fun startRecognizer(context: Context, target: VoiceTarget, locale: String): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return false
        stopRecognizer()
        val component = try {
            val pm = context.packageManager
            try {
                pm.getServiceInfo(ComponentName(PKG, RECOGNIZER), 0)
                ComponentName(PKG, RECOGNIZER)
            } catch (_: Exception) { null }
        } catch (_: Exception) { null }
        val rec = try {
            if (component != null && Build.VERSION.SDK_INT >= 21)
                SpeechRecognizer.createSpeechRecognizer(context, component)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            android.util.Log.w("KlVoice", "create SpeechRecognizer", e)
            return false
        } ?: return false
        recognizer = rec
        if (!target.beginSession()) { stopRecognizer(); return false }
        val main = Handler(Looper.getMainLooper())
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                main.post {
                    target.endVoice()
                    stopRecognizer()
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        Toast.makeText(context, R.string.kl_voice_google_need_mic, Toast.LENGTH_LONG).show()
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val raw = texts?.firstOrNull().orEmpty()
                main.post {
                    val text = ru.klaviatura.code.CodeVoice.maybeRewrite(raw, locale)
                    if (text.isNotBlank()) target.commitPhrase(text)
                    target.endVoice()
                    stopRecognizer()
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val raw = texts?.firstOrNull().orEmpty()
                if (raw.isNotBlank()) main.post {
                    target.showPartial(ru.klaviatura.code.CodeVoice.maybeRewrite(raw, locale))
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag(locale))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            setPackage(PKG)
        }
        return try { rec.startListening(intent); true } catch (e: Exception) {
            android.util.Log.w("KlVoice", "startListening", e)
            stopRecognizer()
            false
        }
    }

    fun toastMissing(context: Context) {
        Toast.makeText(context, R.string.kl_voice_google_missing, Toast.LENGTH_LONG).show()
    }

    private fun localeTag(lang: String) = when (lang.lowercase(Locale.US).substringBefore('-')) {
        "ru" -> "ru-RU"; "uk" -> "uk-UA"; "en" -> "en-US"; else -> lang
    }
}
