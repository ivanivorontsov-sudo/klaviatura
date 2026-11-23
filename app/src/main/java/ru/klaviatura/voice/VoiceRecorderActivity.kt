// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.voice

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import kotlin.concurrent.thread

/**
 * Local voice tools: recorder, audio-file transcription, review/edit and explicit text insertion.
 * Transcription never submits the active form or sends audio to a server.
 */
class VoiceRecorderActivity : Activity() {
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var output: File? = null
    private var selectedAudio: Uri? = null
    private lateinit var status: TextView
    private lateinit var recordButton: Button
    private lateinit var transcript: EditText
    private lateinit var transcribeButton: Button
    private var recording = false
    private var insertedText = false
    private var language = "ru"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceInput.init(this)
        language = VoiceInput.currentLang().takeIf { VoiceInput.supported(it) } ?: "ru"
        title = "Голосовые инструменты"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        status = TextView(this).apply {
            text = "Записи и расшифровка обрабатываются на устройстве"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 14)
        }
        recordButton = Button(this).apply { text = "● Начать запись"; setOnClickListener { toggleRecording() } }
        val play = Button(this).apply { text = "▶ Прослушать запись"; setOnClickListener { playRecording() } }
        val share = Button(this).apply { text = "Отправить аудиофайл…"; setOnClickListener { shareRecording() } }
        val choose = Button(this).apply { text = "Выбрать аудиофайл…"; setOnClickListener { chooseAudio() } }
        transcribeButton = Button(this).apply {
            text = "Расшифровать локально"
            isEnabled = false
            setOnClickListener { transcribeSelected() }
        }
        transcript = EditText(this).apply {
            hint = "Здесь появится текст. Проверьте и отредактируйте его перед вставкой."
            minLines = 4
            maxLines = 12
            gravity = Gravity.TOP
            textSize = 16f
        }
        val insert = Button(this).apply {
            text = "Вставить текст в поле ввода"
            setOnClickListener {
                val value = transcript.text?.toString().orEmpty()
                if (VoiceInput.commitImportedText(value)) {
                    insertedText = true
                    Toast.makeText(this@VoiceRecorderActivity, "Текст вставлен. Ничего не отправлено.", Toast.LENGTH_LONG).show()
                    finish()
                } else Toast.makeText(this@VoiceRecorderActivity, "Не удалось вставить: вернитесь в поле ввода и откройте диктофон с клавиатуры ещё раз", Toast.LENGTH_LONG).show()
            }
        }
        val close = Button(this).apply { text = "Закрыть"; setOnClickListener { finish() } }
        listOf(status, recordButton, play, share, choose, transcribeButton, transcript, insert, close).forEach { view ->
            root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
    }

    private fun chooseAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, 42)
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        selectedAudio = uri
        transcribeButton.isEnabled = true
        status.text = "Файл выбран. Нажмите «Расшифровать локально»."
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
    }

    private fun transcribeSelected() {
        val uri = selectedAudio ?: return
        if (!VoiceInput.hasModel(language)) {
            status.text = "Скачиваю офлайн-модель распознавания для $language. После загрузки нажмите ещё раз."
            transcribeButton.isEnabled = false
            VoiceInput.download(language)
            transcribeButton.postDelayed({ transcribeButton.isEnabled = selectedAudio != null }, 1500)
            return
        }
        transcribeButton.isEnabled = false
        status.text = "Расшифровываю на устройстве… это может занять время"
        thread(name = "kl-audio-transcribe") {
            try {
                val text = AudioFileTranscriber.transcribe(this, uri, VoiceInput.modelDir(language).absolutePath)
                runOnUiThread {
                    transcript.setText(VoiceText.format(text, language, commands = false))
                    status.text = if (text.isBlank()) "Речь не распознана. Попробуйте другой файл или модель." else "Готово. Проверьте текст — он пока никуда не вставлен."
                    transcribeButton.isEnabled = true
                }
            } catch (e: Exception) {
                android.util.Log.e("KlVoice", "audio file transcription failed", e)
                runOnUiThread {
                    status.text = "Не удалось расшифровать: ${e.message ?: e.javaClass.simpleName}"
                    transcribeButton.isEnabled = true
                }
            }
        }
    }

    private fun toggleRecording() {
        if (recording) stopRecording() else {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41)
                return
            }
            startRecording()
        }
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun startRecording() {
        runCatching {
            val dir = File(filesDir, "recordings").apply { mkdirs() }
            output = File(dir, "voice-${System.currentTimeMillis()}.m4a")
            val r = createRecorder()
            recorder = r
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(output!!.absolutePath)
            r.prepare()
            r.start()
            recording = true
            recordButton.text = "■ Остановить"
            status.text = "Идёт запись… файл остаётся на устройстве"
        }.onFailure {
            releaseRecorder()
            Toast.makeText(this, "Не удалось начать запись: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        if (!recording) return
        runCatching { recorder?.stop() }.onFailure { output?.delete() }
        releaseRecorder()
        recording = false
        recordButton.text = "● Записать ещё"
        status.text = if (output?.isFile == true) "Запись готова: ${output!!.name}" else "Запись не сохранена"
    }

    private fun releaseRecorder() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
    }

    private fun playRecording() {
        val file = output?.takeIf { it.isFile } ?: run {
            Toast.makeText(this, "Сначала сделайте запись", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { it.release(); player = null }
                prepare()
                start()
            }
            status.text = "Воспроизведение…"
        }.onFailure { Toast.makeText(this, "Не удалось воспроизвести запись", Toast.LENGTH_SHORT).show() }
    }

    private fun shareRecording() {
        if (recording) stopRecording()
        val file = output?.takeIf { it.isFile } ?: run {
            Toast.makeText(this, "Сначала сделайте запись", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.voicefiles", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Отправить аудиозапись"))
            status.text = "Выберите приложение для отправки аудиофайла"
        }.onFailure {
            Toast.makeText(this, "Не удалось подготовить аудиофайл: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
        else if (requestCode == 41) Toast.makeText(this, "Для записи нужен доступ к микрофону", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        if (!insertedText) VoiceInput.cancelExternalInsert()
        if (recording) stopRecording()
        runCatching { player?.release() }
        player = null
        super.onDestroy()
    }
}
