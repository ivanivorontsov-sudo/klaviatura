// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.clip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.text.Html
import com.googlecode.tesseract.android.TessBaseAPI
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Offline screenshot/photo OCR. Language data is fetched once from the official
 * tessdata_fast repository; recognition itself never uploads the image.
 *
 * Output contains both readable text and a machine-readable layout map with pixel
 * bounding boxes for every recognized line and word.
 */
object ScreenshotOcr {
    private const val MAX_IMAGE_SIDE = 3600
    private const val MIN_MODEL_BYTES = 300_000L
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "KlaviaturaScreenshotOCR").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private val linePattern = Regex(
        """<span\b(?=[^>]*\bclass=['"][^'"]*\bocr_line\b[^'"]*['"])(?=[^>]*\btitle=['"][^'"]*bbox\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)[^'"]*['"])[^>]*>(.*?)</span>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val wordPattern = Regex(
        """<span\b(?=[^>]*\bclass=['"][^'"]*\bocrx_word\b[^'"]*['"])(?=[^>]*\btitle=['"][^'"]*bbox\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)[^'"]*['"])[^>]*>(.*?)</span>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun recognize(context: Context, imageFile: File, callback: (text: String?, error: String?) -> Unit) {
        worker.execute {
            try {
                val output = recognizeBlocking(context.applicationContext, imageFile)
                main.post { callback(output, null) }
            } catch (e: Exception) {
                val message = e.message?.takeIf { it.isNotBlank() } ?: "неизвестная ошибка OCR"
                main.post { callback(null, message) }
            }
        }
    }

    private fun recognizeBlocking(context: Context, imageFile: File): String {
        if (!imageFile.isFile || imageFile.length() <= 0L) throw IOException("файл изображения недоступен")
        val dataRoot = File(context.filesDir, "ocr/tesseract")
        val tessdata = File(dataRoot, "tessdata")
        if (!tessdata.exists() && !tessdata.mkdirs()) throw IOException("не удалось создать каталог OCR")
        ensureModel(File(tessdata, "rus.traineddata"), "rus")
        ensureModel(File(tessdata, "eng.traineddata"), "eng")

        val bitmap = decodeImage(imageFile)
        val api = TessBaseAPI()
        try {
            if (!api.init(dataRoot.absolutePath, "rus+eng"))
                throw IOException("Tesseract не смог загрузить русскую и английскую модели")
            api.setVariable("preserve_interword_spaces", "1")
            api.setImage(bitmap)
            val plain = api.UTF8Text.orEmpty().trim()
            val hocr = api.getHOCRText(0).orEmpty()
            if (plain.isBlank() && hocr.isBlank()) throw IOException("на изображении не найден читаемый текст")
            return formatResult(plain, hocr, bitmap.width, bitmap.height)
        } finally {
            runCatching { api.recycle() }
            bitmap.recycle()
        }
    }

    private fun ensureModel(target: File, language: String) {
        if (target.isFile && target.length() >= MIN_MODEL_BYTES) return
        val temporary = File(target.parentFile, target.name + ".part")
        val url = URL("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/$language.traineddata")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Klaviatura-OCR/1.0")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) throw IOException("не удалось скачать модель $language (HTTP ${connection.responseCode})")
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            if (temporary.length() < MIN_MODEL_BYTES)
                throw IOException("скачанная модель $language повреждена или неполная")
            if (target.exists() && !target.delete()) throw IOException("не удалось заменить модель $language")
            if (!temporary.renameTo(target)) throw IOException("не удалось сохранить модель $language")
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun decodeImage(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("не удалось прочитать изображение")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IOException("не удалось декодировать изображение")
        val rotation = runCatching { ExifInterface(file).rotationDegrees }.getOrDefault(0)
        if (rotation == 0) return decoded
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } catch (e: Exception) {
            decoded.recycle()
            throw IOException("не удалось повернуть фотографию", e)
        }
    }

    private data class Word(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val confidence: Int?
    )

    private data class Line(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val words: List<Word>
    )

    private fun formatResult(plain: String, hocr: String, width: Int, height: Int): String {
        val lines = linePattern.findAll(hocr).mapNotNull { match ->
            val left = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val top = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val right = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            val bottom = match.groupValues[4].toIntOrNull() ?: return@mapNotNull null
            val raw = match.groupValues[5]
            val words = wordPattern.findAll(raw).mapNotNull { wordMatch ->
                val x1 = wordMatch.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val y1 = wordMatch.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val x2 = wordMatch.groupValues[3].toIntOrNull() ?: return@mapNotNull null
                val y2 = wordMatch.groupValues[4].toIntOrNull() ?: return@mapNotNull null
                val wordText = htmlText(wordMatch.groupValues[5])
                if (wordText.isBlank()) return@mapNotNull null
                val confidence = Regex("""x_wconf\s+(\\d+)""").find(wordMatch.value)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                Word(wordText, x1, y1, x2, y2, confidence)
            }.toList()
            val lineText = words.joinToString(" ") { it.text }.ifBlank { htmlText(raw) }
            if (lineText.isBlank()) null else Line(lineText, left, top, right, bottom, words)
        }.toList()

        val readable = if (lines.isNotEmpty()) {
            val medianHeight = lines.map { (it.bottom - it.top).coerceAtLeast(1) }.sorted()
                .let { it[it.size / 2] }
            buildString {
                lines.forEachIndexed { index, line ->
                    if (index > 0) {
                        val gap = line.top - lines[index - 1].bottom
                        append(if (gap > medianHeight * 0.9) "\\n\\n" else "\\n")
                    }
                    append(line.text)
                }
            }
        } else plain

        val jsonLines = JSONArray()
        lines.forEachIndexed { index, line ->
            val words = JSONArray()
            line.words.forEach { word ->
                words.put(JSONObject()
                    .put("text", word.text)
                    .put("bbox_px", bbox(word.left, word.top, word.right, word.bottom))
                    .put("confidence_percent", word.confidence ?: JSONObject.NULL))
            }
            jsonLines.put(JSONObject()
                .put("order", index + 1)
                .put("text", line.text)
                .put("bbox_px", bbox(line.left, line.top, line.right, line.bottom))
                .put("words", words))
        }
        val structure = JSONObject()
            .put("schema", "klaviatura.screenshot-ocr.v1")
            .put("engine", "Tesseract 5 / tessdata_fast rus+eng")
            .put("image", JSONObject().put("width_px", width).put("height_px", height))
            .put("coordinate_system", "pixel; origin at top-left; bbox=[left,top,right,bottom]")
            .put("reading_order", "top-to-bottom as detected by Tesseract")
            .put("text", readable)
            .put("lines", jsonLines)

        return buildString {
            append("РАСПОЗНАННЫЙ ТЕКСТ\\n")
            append(readable.ifBlank { plain })
            append("\\n\\nСТРУКТУРА И РАСПОЛОЖЕНИЕ (JSON)\\n")
            append("Координаты привязаны к размеру исходного изображения; слова имеют отдельные рамки.\\n")
            append("```json\\n")
            append(structure.toString(2))
            append("\\n```")
        }
    }

    private fun bbox(left: Int, top: Int, right: Int, bottom: Int) = JSONArray()
        .put(left).put(top).put(right).put(bottom)

    @Suppress("DEPRECATION")
    private fun htmlText(value: String): String =
        Html.fromHtml(value.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\\n"),
            Html.FROM_HTML_MODE_LEGACY).toString().replace('\\u00a0', ' ').trim()
}
