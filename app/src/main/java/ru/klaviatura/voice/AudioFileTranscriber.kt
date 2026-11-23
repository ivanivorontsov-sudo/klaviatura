// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.voice

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Decodes common Android audio formats with MediaCodec, downmixes to mono 16 kHz PCM,
 * then transcribes locally with the existing Vosk runtime.
 *
 * Both source and PCM are temporary cache files. Audio is never uploaded or copied to public storage.
 * PCM is streamed to Vosk in chunks to avoid holding long recordings in RAM.
 */
object AudioFileTranscriber {
    private const val TARGET_RATE = 16_000
    private const val MAX_SOURCE_BYTES = 1024L * 1024L * 1024L

    fun transcribe(context: Context, uri: Uri, modelDirectory: String): String {
        val source = File.createTempFile("kl-audio-source-", ".bin", context.cacheDir)
        val pcm = File.createTempFile("kl-audio-pcm-", ".raw", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(source).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_SOURCE_BYTES) { "Файл больше 1 ГБ — выберите более короткую запись" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalArgumentException("Не удалось открыть выбранный файл")
            decodeToPcm16(source, pcm)
            require(pcm.length() > 0L) { "В аудиофайле не найден звук" }

            val model = Model(modelDirectory)
            val recognizer = Recognizer(model, TARGET_RATE.toFloat())
            return try {
                BufferedInputStream(FileInputStream(pcm)).use { input ->
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        recognizer.acceptWaveForm(chunk, count)
                    }
                }
                JSONObject(recognizer.finalResult).optString("text").trim()
            } finally {
                recognizer.close()
                model.close()
            }
        } finally {
            source.delete()
            pcm.delete()
        }
    }

    private fun decodeToPcm16(source: File, destination: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; format = candidate; break }
            }
            require(track >= 0 && format != null) { "В выбранном файле нет аудиодорожки" }
            extractor.selectTrack(track)
            val mime = format!!.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleRate = format!!.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format!!.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT

            FileOutputStream(destination).buffered().use { pcmOut ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex)!!
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFormat = codec.outputFormat
                            sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (outFormat.containsKey("pcm-encoding"))
                                pcmEncoding = outFormat.getInteger("pcm-encoding")
                        }
                        outputIndex >= 0 -> {
                            val buffer = codec.getOutputBuffer(outputIndex)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                appendResampledMono(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), pcmOut, sampleRate, channels, pcmEncoding)
                            }
                            outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun appendResampledMono(
        source: ByteBuffer,
        output: java.io.OutputStream,
        sourceRate: Int,
        channels: Int,
        encoding: Int
    ) {
        if (sourceRate <= 0 || channels <= 0) return
        val frames = when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> source.remaining() / (4 * channels)
            android.media.AudioFormat.ENCODING_PCM_8BIT -> source.remaining() / channels
            else -> source.remaining() / (2 * channels)
        }
        if (frames <= 0) return
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += when (encoding) {
                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> source.getFloat()
                    android.media.AudioFormat.ENCODING_PCM_8BIT -> ((source.get().toInt() and 0xff) - 128) / 128f
                    else -> source.getShort() / 32768f
                }
            }
            mono[frame] = (sum / channels).coerceIn(-1f, 1f)
        }
        val outFrames = (frames.toLong() * TARGET_RATE / sourceRate).toInt().coerceAtLeast(1)
        val bytes = ByteBuffer.allocate(outFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outFrames) {
            val position = i.toDouble() * sourceRate / TARGET_RATE
            val left = position.toInt().coerceIn(0, frames - 1)
            val right = (left + 1).coerceAtMost(frames - 1)
            val fraction = (position - left).toFloat()
            val value = mono[left] + (mono[right] - mono[left]) * fraction
            bytes.putShort((value * 32767f).roundToInt().toShort())
        }
        output.write(bytes.array())
    }
}
