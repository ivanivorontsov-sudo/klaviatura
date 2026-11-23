// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.voice

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import kotlin.concurrent.thread

/**
 * Local voice tools and the keyboard's recordings library.
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
    private lateinit var recordingLibrary: LinearLayout
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
            text = "Записи хранятся локально на устройстве"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 14)
        }
        val libraryTitle = TextView(this).apply {
            text = "Записи, созданные клавиатурой"
            textSize = 18f
            setPadding(0, 12, 0, 6)
        }
        recordingLibrary = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        recordButton = Button(this).apply { text = "● Начать запись"; setOnClickListener { toggleRecording() } }
        val play = Button(this).apply { text = "▶ Прослушать последнюю запись"; setOnClickListener { playRecording() } }
        val share = Button(this).apply { text = "Прикрепить последнюю запись…"; setOnClickListener { shareRecording() } }
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

        listOf(status, libraryTitle, recordingLibrary, recordButton, play, share, choose, transcribeButton, transcript, insert, close).forEach { view ->
            root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        refreshRecordingLibrary()

        intent.getStringExtra(EXTRA_TRIM_FILE)?.let { path ->
            val file = File(path)
            if (file.isFile) window.decorView.post { showTrimDialog(file) }
        }
    }

    private fun refreshRecordingLibrary() {
        recordingLibrary.removeAllViews()
        val files = KeyboardAudioRecorder.recordings(this)
        if (files.isEmpty()) {
            recordingLibrary.addView(TextView(this).apply {
                text = "Пока нет сохранённых записей. Удерживайте второй микрофон на клавиатуре, чтобы начать."
                textSize = 14f
                setPadding(0, 4, 0, 12)
            })
            return
        }

        files.forEach { file ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10, 8, 10, 8)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x14000000)
                    cornerRadius = 10 * resources.displayMetrics.density
                }
            }
            val sizeMb = file.length() / 1_048_576.0
            val duration = audioDurationMs(file)
            val info = TextView(this).apply {
                text = "${file.name}\n${formatDuration(duration)} · %.1f МБ".format(sizeMb)
                textSize = 14f
            }
            card.addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            actions.addView(libraryButton("▶ Слушать") { playFile(file) })
            actions.addView(libraryButton("Текст") { transcribeFile(file) })
            actions.addView(libraryButton("Обрезать") { showTrimDialog(file) })
            actions.addView(libraryButton("Отправить") { KeyboardAudioRecorder.shareFile(this@VoiceRecorderActivity, file) })
            actions.addView(libraryButton("Удалить") { confirmDelete(file) })
            card.addView(actions)
            recordingLibrary.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (6 * resources.displayMetrics.density).toInt()
            })
        }
    }

    private fun libraryButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        minWidth = 0
        setPadding(5, 0, 5, 0)
        setOnClickListener { action() }
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Удалить запись?")
            .setMessage(file.name)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                if (file.delete()) {
                    if (output == file) output = null
                    status.text = "Запись удалена"
                    refreshRecordingLibrary()
                } else Toast.makeText(this, "Не удалось удалить файл", Toast.LENGTH_SHORT).show()
            }
            .show()
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
        transcribeUri(uri)
    }

    private fun transcribeFile(file: File) {
        val uri = runCatching { FileProvider.getUriForFile(this, "$packageName.voicefiles", file) }.getOrNull() ?: return
        transcribeButton.isEnabled = true
        selectedAudio = uri
        transcribeUri(uri)
    }

    private fun transcribeUri(uri: Uri) {
        selectedAudio = uri
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
                    transcribeButton.isEnabled = selectedAudio != null
                }
            } catch (e: Exception) {
                android.util.Log.e("KlVoice", "audio file transcription failed", e)
                runOnUiThread {
                    status.text = "Не удалось расшифровать: ${e.message ?: e.javaClass.simpleName}"
                    transcribeButton.isEnabled = selectedAudio != null
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
        refreshRecordingLibrary()
    }

    private fun releaseRecorder() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
    }

    private fun playRecording() {
        val file = output?.takeIf { it.isFile } ?: KeyboardAudioRecorder.recordings(this).firstOrNull()
        if (file == null) {
            Toast.makeText(this, "Сначала сделайте запись", Toast.LENGTH_SHORT).show()
            return
        }
        playFile(file)
    }

    private fun playFile(file: File) {
        runCatching {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { it.release(); player = null }
                prepare()
                start()
            }
            status.text = "Воспроизведение: ${file.name}"
        }.onFailure { Toast.makeText(this, "Не удалось воспроизвести запись", Toast.LENGTH_SHORT).show() }
    }

    private fun shareRecording() {
        if (recording) stopRecording()
        val file = output?.takeIf { it.isFile } ?: KeyboardAudioRecorder.recordings(this).firstOrNull()
        if (file == null) {
            Toast.makeText(this, "Сначала сделайте запись", Toast.LENGTH_SHORT).show()
            return
        }
        KeyboardAudioRecorder.shareFile(this, file)
        status.text = "Выберите приложение или чат для отправки аудиофайла"
    }

    private fun audioDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs.coerceAtLeast(0L) / 1_000L
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun showTrimDialog(file: File) {
        if (!file.isFile) return
        val durationMs = audioDurationMs(file)
        if (durationMs < 1_000L) {
            Toast.makeText(this, "Запись слишком короткая для обрезки", Toast.LENGTH_SHORT).show()
            return
        }

        var startMs = 0L
        var endMs = durationMs
        val minLength = minOf(1_000L, durationMs / 4L)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        val startLabel = TextView(this)
        val startSlider = SeekBar(this).apply { max = 1000; progress = 0 }
        val endLabel = TextView(this)
        val endSlider = SeekBar(this).apply { max = 1000; progress = 1000 }
        fun updateLabels() {
            startLabel.text = "Начало: ${formatDuration(startMs)}"
            endLabel.text = "Конец: ${formatDuration(endMs)}"
        }
        startSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                startMs = durationMs * progress / 1000L
                if (endMs - startMs < minLength) startMs = (endMs - minLength).coerceAtLeast(0L)
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        endSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                endMs = durationMs * progress / 1000L
                if (endMs - startMs < minLength) endMs = (startMs + minLength).coerceAtMost(durationMs)
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        panel.addView(startLabel)
        panel.addView(startSlider)
        panel.addView(endLabel)
        panel.addView(endSlider)
        updateLabels()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Обрезать запись")
            .setMessage("Выберите начало и конец фрагмента. Исходный файл останется нетронутым.")
            .setView(panel)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить копию", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                status.text = "Обрезаю аудио без повторного сжатия…"
                thread(name = "kl-audio-trim") {
                    try {
                        val trimmed = AudioTrimmer.trimToCopy(file, startMs, endMs)
                        runOnUiThread {
                            dialog.dismiss()
                            status.text = "Обрезанная копия сохранена: ${trimmed.name}"
                            refreshRecordingLibrary()
                            Toast.makeText(this, "Готово: ${trimmed.name}", Toast.LENGTH_LONG).show()
                        }
                    } catch (error: Exception) {
                        android.util.Log.e("KlVoice", "audio trim failed", error)
                        runOnUiThread {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            status.text = "Не удалось обрезать: ${error.message ?: error.javaClass.simpleName}"
                            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        dialog.show()
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

    companion object {
        const val EXTRA_TRIM_FILE = "ru.klaviatura.voice.extra.TRIM_FILE"
    }
}
