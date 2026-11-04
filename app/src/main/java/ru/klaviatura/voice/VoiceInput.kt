// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.utils.prefs
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.concurrent.thread

/**
 * Built-in offline voice input (Vosk). Bound to the language of the current layout; mixed mode
 * (ru+en at once) is an optional toggle. Text is put as composing text at the cursor and committed
 * at the end of each phrase, so the cursor and existing text are never lost.
 */
object VoiceInput {
    const val PREF_ENGINE = "kl_voice_builtin"   // true = built-in, false = system voice IME
    const val PREF_MIXED = "kl_voice_mixed"
    const val PREF_COMMANDS = "kl_voice_commands"

    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    @Volatile var listening = false; private set
    @Volatile private var stopRequested = false
    @Volatile private var restartLang: String? = null
    private val loaded = HashMap<String, Model>()
    private val downloading = HashSet<String>()
    var target: VoiceTarget? = null
    var onStateChanged: (() -> Unit)? = null

    fun init(context: Context) { ctx = context.applicationContext }

    fun builtinEnabled(): Boolean = ctx?.prefs()?.getBoolean(PREF_ENGINE, true) ?: true
    private fun mixed() = ctx?.prefs()?.getBoolean(PREF_MIXED, false) ?: false

    /** language of the active layout; voice always follows it */
    fun currentLang(): String = try {
        RichInputMethodManager.getInstance().currentSubtypeLocale.language.ifEmpty { "en" }
    } catch (e: Exception) { "en" }

    private fun secondLang(primary: String) = if (primary == "ru") "en" else "ru"

    /** short label for the mic key */
    fun label(): String {
        progress[currentLang()]?.let { return if (it < 0) "распак." else "$it%" }
        val l = currentLang().uppercase()
        return if (mixed()) "$l+${secondLang(currentLang()).uppercase()}" else l
    }

    fun modelDir(lang: String) = File(ctx!!.filesDir, "vosk/$lang")
    fun hasModel(lang: String) = ModelDownloader.isModelDir(modelDir(lang))
    fun supported(lang: String) = ModelDownloader.MIRRORS.containsKey(lang)

    fun toggle(t: VoiceTarget) { if (listening) stop() else start(t) }

    fun stop() { stopRequested = true; refresh() }

    /** layout switched -> switch voice language immediately */
    fun onLayoutChanged() {
        onStateChanged?.invoke()
        if (listening) { restartLang = currentLang() }
    }

    private fun toast(s: String) = main.post { ctx?.let { Toast.makeText(it, s, Toast.LENGTH_SHORT).show() } }

    fun start(t: VoiceTarget) {
        val c = ctx ?: return
        target = t
        val lang = currentLang()
        if (!supported(lang)) { toast("Голос для языка «$lang» пока не поддерживается"); return }
        val langs = if (mixed()) listOf(lang, secondLang(lang)) else listOf(lang)
        // ROOT CAUSE FIX (1.2.0): earlier the mic permission was checked first and the method returned,
        // so on first use the download never started (the permission screen just closed). Now the model
        // download starts immediately, independent of the mic permission.
        val missing = langs.filter { !hasModel(it) }
        if (missing.isNotEmpty()) missing.forEach { download(it) }
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            try { c.startActivity(Intent(c, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { toast("Разрешите доступ к микрофону в настройках приложения") }
            return
        }
        if (missing.isNotEmpty()) return
        listening = true; stopRequested = false; restartLang = null
        onStateChanged?.invoke()
        thread(name = "kl-voice") { runLoop(langs) }
    }

    /** download progress per language, shown on the mic key: 0..100, or -1 = unzipping */
    val progress = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun download(lang: String) {
        val c = ctx ?: return
        if (!downloading.add(lang)) { toast("Модель «$lang» ещё скачивается — прогресс в уведомлении"); return }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            try { c.startActivity(Intent(c, VoicePermissionActivity::class.java).putExtra("notif", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        val n = DownloadNotifier(c, lang)
        toast("Скачиваю офлайн-модель голоса ($lang). Прогресс — на кнопке микрофона и в уведомлении")
        progress[lang] = 0; refresh()
        thread(name = "kl-voice-dl") {
            try {
                modelDir(lang).parentFile?.mkdirs()
                var lastPct = -1; var lastT = 0L
                ModelDownloader.download(lang, modelDir(lang), object : ModelDownloader.Listener {
                    override fun onProgress(done: Long, total: Long, mirror: Int) {
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        val now = System.currentTimeMillis()
                        if (pct != lastPct && now - lastT > 300) { lastPct = pct; lastT = now; progress[lang] = pct; n.progress(pct, done, total); refresh() }
                    }
                    override fun onUnzip() { progress[lang] = -1; n.unzipping(); refresh() }
                    override fun onMirrorFailed(url: String, error: String) { android.util.Log.w("KlVoice", "mirror failed $url: $error") }
                })
                n.done()
                toast("Модель голоса «$lang» готова — нажмите микрофон")
            } catch (e: Exception) {
                android.util.Log.e("KlVoice", "download failed", e)
                val msg = ModelDownloader.humanError(e)
                n.error(msg)
                toast("Не удалось скачать модель «$lang»: $msg. «Повторить» в уведомлении или импорт из файла в настройках")
            } finally { downloading.remove(lang); progress.remove(lang); refresh() }
        }
    }

    fun mb(b: Long) = "%.1f".format(b / 1048576.0)
    private fun refresh() = main.post { onStateChanged?.invoke() }

    private fun model(lang: String): Model = synchronized(loaded) { loaded.getOrPut(lang) { Model(modelDir(lang).absolutePath) } }

    /** microphone level 0..1 for the recording indicator */
    @Volatile var level = 0f; private set
    fun statusText() = if (listening) "Слушаю… (${currentLang().uppercase()})" else ""

    private class VoskAsr(m: Model, rate: Float) : Asr {
        val r = Recognizer(m, rate).apply { setWords(true) }
        override fun accept(buf: ShortArray, n: Int) = r.acceptWaveForm(buf, n)
        override fun result(): String = r.result
        override fun partial(): String = r.partialResult
        override fun final(): String = r.finalResult
        override fun close() = r.close()
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        for (src in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val r = AudioRecord.Builder().setAudioSource(src)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min, 8192) * 2).build()
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                android.util.Log.i("KlVoice", "AudioRecord src=$src rate=${r.sampleRate} ch=${r.channelCount} fmt=${r.audioFormat}")
                return r
            }
            r.release()
        }
        throw IllegalStateException("микрофон недоступен")
    }

    private fun runLoop(initial: List<String>) {
        var rec: AudioRecord? = null
        var session: VoiceSession? = null
        val out = object : VoiceOutput {
            override fun partial(text: String) { main.post { target?.showPartial(text) } }
            override fun commit(text: String) { main.post { target?.commitPhrase(text) } }
        }
        try {
            rec = openRecorder()
            // the recognizer gets the REAL rate of the recorder (Vosk resamples if it differs from 16 kHz)
            val rate = rec.sampleRate.toFloat()
            val commands = ctx?.prefs()?.getBoolean(PREF_COMMANDS, true) != false
            session = VoiceSession(initial.map { it to (VoskAsr(model(it), rate) as Asr) }, out, commands)
            rec.startRecording()
            val buf = ShortArray(1600) // 100 ms
            var lastUi = 0L
            while (!stopRequested) {
                restartLang?.let { nl ->
                    restartLang = null
                    if (!hasModel(nl)) { download(nl); stopRequested = true; return@let }
                    val langs = if (mixed()) listOf(nl, secondLang(nl)).filter { hasModel(it) } else listOf(nl)
                    session.replace(langs.map { it to (VoskAsr(model(it), rate) as Asr) })
                }
                if (stopRequested) break
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { if (n < 0) throw IllegalStateException("ошибка чтения микрофона $n"); continue }
                var sum = 0.0; for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
                level = (kotlin.math.sqrt(sum / n) * 6).toFloat().coerceIn(0f, 1f)
                session.feed(buf, n)
                val now = System.currentTimeMillis()
                if (now - lastUi > 120) { lastUi = now; refresh() }
                if (now - session.lastSpeechAt > 6000) break // silence -> stop
            }
            session.finish()
        } catch (e: Exception) {
            android.util.Log.e("KlVoice", "voice loop", e)
            toast("Ошибка голосового ввода: ${e.message}")
        } finally {
            try { rec?.stop(); rec?.release() } catch (_: Exception) {}
            session?.close()
            listening = false; level = 0f
            main.post { target?.endVoice(); onStateChanged?.invoke() }
        }
    }

}

/** implemented by the IME: where voice text goes */
interface VoiceTarget {
    fun showPartial(text: String)
    fun commitPhrase(text: String)
    fun endVoice()
}
