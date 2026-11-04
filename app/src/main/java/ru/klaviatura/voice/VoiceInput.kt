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

    fun stop() { stopRequested = true }

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

    @SuppressLint("MissingPermission")
    private fun runLoop(initial: List<String>) {
        var langs = initial
        val rate = 16000
        val bufSize = maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 4096)
        var rec: AudioRecord? = null
        var recognizers: List<Pair<String, Recognizer>> = emptyList()
        try {
            recognizers = langs.map { l -> l to Recognizer(model(l), rate.toFloat()).apply { setWords(true) } }
            rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            rec.startRecording()
            val buf = ShortArray(bufSize / 2)
            var lastSpeech = System.currentTimeMillis()
            while (!stopRequested) {
                restartLang?.let { nl ->
                    // layout changed: finish the current phrase, then continue in the new language
                    finishPhrase(recognizers, final = true)
                    recognizers.forEach { it.second.close() }
                    langs = if (mixed()) listOf(nl, secondLang(nl)).filter { hasModel(it) } else listOf(nl)
                    if (!hasModel(nl)) { download(nl); stopRequested = true; return@let }
                    recognizers = langs.map { l -> l to Recognizer(model(l), rate.toFloat()).apply { setWords(true) } }
                    restartLang = null
                }
                if (stopRequested) break
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var phraseDone = false
                for ((_, r) in recognizers) if (r.acceptWaveForm(buf, n)) phraseDone = true
                if (phraseDone) { if (finishPhrase(recognizers, false)) lastSpeech = System.currentTimeMillis() }
                else {
                    val partial = JSONObject(recognizers[0].second.partialResult).optString("partial")
                    if (partial.isNotBlank()) { lastSpeech = System.currentTimeMillis(); val p = format(partial, recognizers[0].first, peek = true); main.post { target?.showPartial(p) } }
                }
                if (System.currentTimeMillis() - lastSpeech > 8000) break // silence -> stop
            }
            finishPhrase(recognizers, true)
        } catch (e: Exception) {
            toast("Ошибка голосового ввода: ${e.message}")
        } finally {
            try { rec?.stop(); rec?.release() } catch (_: Exception) {}
            recognizers.forEach { try { it.second.close() } catch (_: Exception) {} }
            listening = false
            main.post { target?.endVoice(); onStateChanged?.invoke() }
        }
    }

    /** picks the best recognizer's result (mixed mode: highest confidence), commits it */
    private fun finishPhrase(recognizers: List<Pair<String, Recognizer>>, final: Boolean): Boolean {
        var best: String? = null; var bestLang = ""; var bestConf = -1.0
        for ((l, r) in recognizers) {
            val js = JSONObject(if (final) r.finalResult else r.result)
            val text = js.optString("text")
            if (text.isBlank()) continue
            val arr = js.optJSONArray("result")
            var conf = 0.5
            if (arr != null && arr.length() > 0) { conf = 0.0; for (i in 0 until arr.length()) conf += arr.getJSONObject(i).optDouble("conf", 0.5); conf /= arr.length() }
            if (l == recognizers[0].first) conf += 0.05 // prefer the layout language on ties
            if (conf > bestConf) { bestConf = conf; best = text; bestLang = l }
        }
        val text = best ?: return false
        val out = format(text, bestLang, peek = false)
        if (out.isNotEmpty()) main.post { target?.commitPhrase(out) }
        return true
    }

    /** keeps only words in the script of the language, applies spoken punctuation */
    fun format(raw: String, lang: String, peek: Boolean): String {
        val cyr = lang in setOf("ru", "uk", "be", "bg", "sr")
        var words = raw.trim().split(Regex("\\s+")).filter { w ->
            w.isNotEmpty() && w.all { ch -> !ch.isLetter() || (if (cyr) ch in '\u0400'..'\u04FF' else ch !in '\u0400'..'\u04FF') }
        }
        if (ctx?.prefs()?.getBoolean(PREF_COMMANDS, true) != false && !peek) words = punctuate(words, lang)
        return words.joinToString(" ").replace(Regex(" ([,.!?:;])"), "$1").replace(" \n ", "\n").trim()
    }

    private fun punctuate(words: List<String>, lang: String): List<String> {
        val cmds = if (lang == "ru") listOf(
            listOf("вопросительный", "знак") to "?", listOf("восклицательный", "знак") to "!",
            listOf("новая", "строка") to "\n", listOf("точка") to ".", listOf("запятая") to ",",
            listOf("двоеточие") to ":", listOf("тире") to " —")
        else listOf(listOf("question", "mark") to "?", listOf("exclamation", "mark") to "!",
            listOf("new", "line") to "\n", listOf("period") to ".", listOf("full", "stop") to ".", listOf("comma") to ",", listOf("colon") to ":")
        val out = ArrayList<String>(); var i = 0
        loop@ while (i < words.size) {
            for ((seq, p) in cmds) if (i + seq.size <= words.size && words.subList(i, i + seq.size).map { it.lowercase() } == seq) {
                out.add(p); i += seq.size; continue@loop
            }
            out.add(words[i]); i++
        }
        return out
    }
}

/** implemented by the IME: where voice text goes */
interface VoiceTarget {
    fun showPartial(text: String)
    fun commitPhrase(text: String)
    fun endVoice()
}
