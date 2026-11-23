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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Decodes common Android audio formats with MediaCodec, downmixes to mono 16 kHz PCM,
 * then transcribes locally with the already-supported Vosk runtime.
 * No audio bytes are uploaded or copied to public storage.
 */
object AudioFileTranscriber {
    private const val TARGET_RATE = 16_000

    fun transcribe(context: Context, uri: Uri, modelDirectory: String): String {
        val pcm = decodeToPcm16(context, uri)
        require(pcm.isNotEmpty()) { "В аудиофайле не найден звук" }
        val model = Model(modelDirectory)
        val recognizer = Recognizer(model, TARGET_RATE.toFloat())
        return try {
            val chunk = 64 * 1024
            var offset = 0
            while (offset < pcm.size) {
                val count = minOf(chunk, pcm.size - offset)
                recognizer.acceptWaveForm(pcm.copyOfRange(offset, offset + count), count)
                offset += count
            }
            JSONObject(recognizer.finalResult).optString("text").trim()
        } finally {
            recognizer.close()
            model.close()
        }
    }

    private fun decodeToPcm16(context: Context, uri: Uri): ByteArray {
        val extractor = MediaExtractor()
        val pcmOut = ByteArrayOutputStream()
        var codec: MediaCodec? = null
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                extractor.setDataSource(fd.fileDescriptor)
            } ?: throw IllegalArgumentException("Не удалось открыть выбранный файл")
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

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)!!
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
                        if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
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
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
        return pcmOut.toByteArray()
    }

    private fun appendResampledMono(
        source: ByteBuffer,
        output: ByteArrayOutputStream,
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
