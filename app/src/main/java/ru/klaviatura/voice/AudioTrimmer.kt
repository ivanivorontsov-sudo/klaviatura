// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.voice

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Copies a selected AAC audio interval into a new M4A file without re-encoding it. */
object AudioTrimmer {
    fun trimToCopy(source: File, startMs: Long, endMs: Long): File {
        require(source.isFile) { "Исходная запись не найдена" }
        require(startMs >= 0 && endMs > startMs) { "Неверный диапазон обрезки" }

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var output: File? = null
        var wroteSamples = false
        try {
            extractor.setDataSource(source.absolutePath)
            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("В файле нет аудиодорожки")

            extractor.selectTrack(audioTrack)
            val format = extractor.getTrackFormat(audioTrack)
            val outputDir = source.parentFile ?: throw IllegalArgumentException("Не удалось определить папку записи")
            output = File(outputDir, "${source.nameWithoutExtension}-trim-${System.currentTimeMillis()}.m4a")
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(format)
            muxer.start()
            muxerStarted = true

            val requestedStartUs = startMs * 1_000L
            val requestedEndUs = endMs * 1_000L
            extractor.seekTo(requestedStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val bufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
            else 256 * 1024
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val info = MediaCodec.BufferInfo()
            var firstTimestampUs = -1L

            while (true) {
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0 || sampleTimeUs > requestedEndUs) break

                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break

                if (sampleTimeUs >= requestedStartUs) {
                    if (firstTimestampUs < 0L) firstTimestampUs = sampleTimeUs
                    info.set(
                        0,
                        size,
                        (sampleTimeUs - firstTimestampUs).coerceAtLeast(0L),
                        extractor.sampleFlags
                    )
                    muxer.writeSampleData(outputTrack, buffer, info)
                    wroteSamples = true
                }
                extractor.advance()
            }

            if (!wroteSamples) throw IllegalArgumentException("В выбранном отрезке нет аудиоданных")
            muxer.stop()
            muxerStarted = false
            return output
        } catch (error: Exception) {
            output?.delete()
            throw error
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}
