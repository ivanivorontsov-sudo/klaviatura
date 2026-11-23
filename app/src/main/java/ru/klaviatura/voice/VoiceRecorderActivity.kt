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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Small local recorder opened by long-pressing the keyboard microphone key.
 * Audio stays in app-private storage until the user explicitly shares it.
 */
class VoiceRecorderActivity : Activity() {
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var output: File? = null
    private lateinit var status: TextView
    private lateinit var recordButton: Button
    private var recording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Диктофон клавиатуры"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        status = TextView(this).apply {
            text = "Запись хранится только на устройстве"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 24)
        }
        recordButton = Button(this).apply { text = "● Начать запись"; setOnClickListener { toggleRecording() } }
        val play = Button(this).apply { text = "▶ Прослушать"; setOnClickListener { playRecording() } }
        val share = Button(this).apply { text = "Вставить запись в чат…"; setOnClickListener { shareRecording() } }
        val close = Button(this).apply { text = "Закрыть"; setOnClickListener { finish() } }
        listOf(status, recordButton, play, share, close).forEach { view ->
            root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
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
            status.text = "Выберите чат или приложение для отправки файла"
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
        if (recording) stopRecording()
        runCatching { player?.release() }
        player = null
        super.onDestroy()
    }
}
