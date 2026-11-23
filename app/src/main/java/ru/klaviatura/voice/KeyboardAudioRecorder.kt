// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.voice

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Recorder owned by the IME, so a long press can start a real audio-file recording without
 * replacing the keyboard with an Activity. Files are kept in the app-private recordings folder.
 */
object KeyboardAudioRecorder {
    enum class State { IDLE, RECORDING, PAUSED, SAVED }

    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var currentFile: File? = null
    @Volatile private var state: State = State.IDLE
    @Volatile private var startedAtMs = 0L
    @Volatile private var pausedAtMs = 0L
    @Volatile private var pausedDurationMs = 0L
    @Volatile private var appContext: Context? = null

    @Volatile var onStateChanged: ((State, File?, Long) -> Unit)? = null

    fun state(): State = state
    fun file(): File? = currentFile?.takeIf { it.isFile }
    fun elapsedMs(): Long {
        val now = if (state == State.PAUSED) pausedAtMs else System.currentTimeMillis()
        return if (startedAtMs == 0L) 0L else (now - startedAtMs - pausedDurationMs).coerceAtLeast(0L)
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(context: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    @Synchronized
    fun start(context: Context): Boolean {
        if (state == State.RECORDING || state == State.PAUSED) return false
        val app = context.applicationContext
        appContext = app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(app, "Разрешите доступ к микрофону и удержите кнопку записи ещё раз", Toast.LENGTH_LONG).show()
            runCatching {
                app.startActivity(Intent(app, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { android.util.Log.w("KlVoice", "cannot open microphone permission screen", it) }
            return false
        }

        val output = File(app.filesDir, "recordings").apply { mkdirs() }
            .let { File(it, "keyboard-${System.currentTimeMillis()}.m4a") }
        var created: MediaRecorder? = null
        return try {
            created = createRecorder(app)
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioEncodingBitRate(128_000)
            created.setAudioSamplingRate(44_100)
            created.setOutputFile(output.absolutePath)
            created.prepare()
            created.start()
            recorder = created
            currentFile = output
            startedAtMs = System.currentTimeMillis()
            pausedAtMs = 0L
            pausedDurationMs = 0L
            state = State.RECORDING
            publish()
            true
        } catch (error: Exception) {
            runCatching { created?.reset() }
            runCatching { created?.release() }
            output.delete()
            android.util.Log.e("KlVoice", "inline recording start failed", error)
            Toast.makeText(app, "Не удалось начать запись: ${error.message ?: error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            false
        }
    }

    @Synchronized
    fun pauseOrResume(): Boolean {
        val r = recorder ?: return false
        return try {
            when (state) {
                State.RECORDING -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                        appContext?.let { Toast.makeText(it, "Пауза записи доступна на Android 7.0 и новее", Toast.LENGTH_SHORT).show() }
                        return false
                    }
                    r.pause()
                    pausedAtMs = System.currentTimeMillis()
                    state = State.PAUSED
                }
                State.PAUSED -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
                    r.resume()
                    pausedDurationMs += System.currentTimeMillis() - pausedAtMs
                    pausedAtMs = 0L
                    state = State.RECORDING
                }
                else -> return false
            }
            publish()
            true
        } catch (error: Exception) {
            android.util.Log.e("KlVoice", "inline recording pause/resume failed", error)
            false
        }
    }

    @Synchronized
    fun stop(): File? {
        if (state != State.RECORDING && state != State.PAUSED) return file()
        val r = recorder
        var stopped = false
        try {
            r?.stop()
            stopped = true
        } catch (error: Exception) {
            android.util.Log.w("KlVoice", "recording was too short or could not be finalized", error)
        } finally {
            runCatching { r?.reset() }
            runCatching { r?.release() }
            recorder = null
        }
        val result = currentFile?.takeIf { stopped && it.isFile && it.length() > 1024L }
        if (result == null) {
            currentFile?.delete()
            currentFile = null
            state = State.IDLE
            startedAtMs = 0L
            pausedAtMs = 0L
            pausedDurationMs = 0L
        } else {
            state = State.SAVED
            pausedAtMs = 0L
        }
        publish()
        return result
    }

    @Synchronized
    fun cancel() {
        if (state == State.RECORDING || state == State.PAUSED) stop()
        currentFile?.delete()
        currentFile = null
        recorder = null
        state = State.IDLE
        startedAtMs = 0L
        pausedAtMs = 0L
        pausedDurationMs = 0L
        publish()
    }

    @Synchronized
    fun deleteCurrent() {
        if (state == State.RECORDING || state == State.PAUSED) stop()
        currentFile?.delete()
        currentFile = null
        state = State.IDLE
        startedAtMs = 0L
        pausedAtMs = 0L
        pausedDurationMs = 0L
        publish()
    }

    fun share(context: Context): Boolean {
        val target = if (state == State.RECORDING || state == State.PAUSED) stop() else file()
        if (target == null) {
            Toast.makeText(context, "Сначала запишите аудио", Toast.LENGTH_SHORT).show()
            return false
        }
        return shareFile(context, target)
    }

    fun shareFile(context: Context, file: File): Boolean {
        if (!file.isFile) return false
        return runCatching {
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.voicefiles", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(send, "Отправить аудиозапись").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.onFailure {
            android.util.Log.e("KlVoice", "cannot share audio recording", it)
            Toast.makeText(context, "Не удалось прикрепить аудиофайл", Toast.LENGTH_LONG).show()
        }.getOrDefault(false)
    }

    fun recordings(context: Context): List<File> =
        File(context.applicationContext.filesDir, "recordings").listFiles()
            ?.filter { it.isFile && it.extension.equals("m4a", true) && it.length() > 1024L }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    private fun publish() {
        val snapshotState = state
        val snapshotFile = currentFile
        val snapshotElapsed = elapsedMs()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onStateChanged?.invoke(snapshotState, snapshotFile, snapshotElapsed)
        }
    }
}
