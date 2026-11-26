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
 * tessdata_best repository; recognition itself never uploads the image.
 *
 * Output contains both readable text and a machine-readable layout map with pixel
 * bounding boxes for every recognized line and word.
 */
object ScreenshotOcr {
    // hOCR word spans carry their own bounding box in the title attribute.
    private val wordPattern = Regex(
        """<span\b(?=[^>]*\bclass=['"][^'"]*\bocrx_word\b[^'"]*['"])(?=[^>]*\btitle=['"][^'"]*\bbbox\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+))[^>]*>(.*?)</span>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private const val MAX_IMAGE_SIDE = 3600
    // Upgrade the previous fast models to Google's higher-accuracy LSTM models.
    private const val MIN_MODEL_BYTES = 13_000_000L
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "KlaviaturaScreenshotOCR").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

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
            if (!api.init(dataRoot.absolutePath, "rus+eng", TessBaseAPI.OEM_LSTM_ONLY))
                throw IOException("Tesseract не смог загрузить точные русскую и английскую модели")
            api.setVariable("preserve_interword_spaces", "1")
            api.setVariable("user_defined_dpi", "300")
            val best = recognizeBest(api, bitmap)
            return formatResult(
                best.plain, best.hocr, bitmap.width, bitmap.height,
                best.confidence, best.pageSegMode
            )
        } finally {
            runCatching { api.recycle() }
            bitmap.recycle()
        }
    }

    private fun ensureModel(target: File, language: String) {
        if (target.isFile && target.length() >= MIN_MODEL_BYTES) return
        val temporary = File(target.parentFile, target.name + ".part")
        val url = URL("https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/main/$language.traineddata")
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

    private data class RecognitionCandidate(
        val plain: String,
        val hocr: String,
        val confidence: Int,
        val pageSegMode: Int,
        val score: Double
    )

    /**
     * Phone screenshots may contain a long article, short UI labels and separate message blocks.
     * Run Tesseract with several page segmentation strategies and select the most complete,
     * highest-confidence result instead of trusting the library's single-block default.
     */
    private fun recognizeBest(api: TessBaseAPI, bitmap: Bitmap): RecognitionCandidate {
        val modes = intArrayOf(
            TessBaseAPI.PageSegMode.PSM_AUTO,
            TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
            TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
        )
        var best: RecognitionCandidate? = null
        for (mode in modes) {
            api.setPageSegMode(mode)
            api.setImage(bitmap)
            val plain = api.getUTF8Text().orEmpty().trim()
            if (plain.isBlank()) continue
            val confidence = api.meanConfidence().coerceIn(0, 100)
            val hocr = api.getHOCRText(0).orEmpty()
            val wordCount = plain.split(Regex("\\s+")).count { it.isNotBlank() }
            // Confidence is primary; the small completeness bonus helps avoid choosing a
            // high-confidence result that silently omitted a large block of screen text.
            val score = confidence.toDouble() + minOf(12.0, wordCount * 0.12)
            val candidate = RecognitionCandidate(plain, hocr, confidence, mode, score)
            if (best == null || candidate.score > best.score) best = candidate
        }
        return best ?: throw IOException("на изображении не найден читаемый текст")
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

    private fun formatResult(
        plain: String,
        hocr: String,
        width: Int,
        height: Int,
        meanConfidence: Int,
        pageSegMode: Int
    ): String {
        val recognizedWords = wordPattern.findAll(hocr).mapNotNull { match ->
            val left = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val top = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val right = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            val bottom = match.groupValues[4].toIntOrNull() ?: return@mapNotNull null
            val wordText = htmlText(match.groupValues[5])
            if (wordText.isBlank()) return@mapNotNull null
            val confidence = Regex("""x_wconf\s+(\d+)""").find(match.value)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            Word(wordText, left, top, right, bottom, confidence)
        }.toList().sortedWith(compareBy<Word> { it.top }.thenBy { it.left })

        // hOCR exposes individual word boxes. Group neighbouring words by their vertical
        // centres instead of parsing nested HTML spans (which can truncate a line at word 1).
        val wordLines = mutableListOf<MutableList<Word>>()
        recognizedWords.forEach { word ->
            val current = wordLines.lastOrNull()
            val wordHeight = (word.bottom - word.top).coerceAtLeast(1)
            val currentCenter = current?.map { (it.top + it.bottom) / 2.0 }?.average()
            val currentHeight = current?.map { (it.bottom - it.top).coerceAtLeast(1) }?.sorted()
                ?.let { it[it.size / 2] }
            val wordCenter = (word.top + word.bottom) / 2.0
            val tolerance = maxOf(4.0, ((currentHeight ?: wordHeight) + wordHeight) * 0.30)
            if (current != null && currentCenter != null && kotlin.math.abs(wordCenter - currentCenter) <= tolerance) {
                current.add(word)
            } else {
                wordLines.add(mutableListOf(word))
            }
        }
        val lines = wordLines.map { row ->
            val ordered = row.sortedBy { it.left }
            Line(
                ordered.joinToString(" ") { it.text },
                ordered.minOf { it.left },
                ordered.minOf { it.top },
                ordered.maxOf { it.right },
                ordered.maxOf { it.bottom },
                ordered
            )
        }.sortedBy { it.top }

        val hocrWordCount = lines.sumOf { it.words.size }
        val plainWordCount = plain.split(Regex("\\s+")).count { it.isNotBlank() }
        val hocrIsComplete = lines.isNotEmpty() &&
            (plainWordCount == 0 || hocrWordCount >= (plainWordCount * 0.78).toInt())
        val readable = if (hocrIsComplete) {
            val medianHeight = lines.map { (it.bottom - it.top).coerceAtLeast(1) }.sorted()
                .let { it[it.size / 2] }
            buildString {
                lines.forEachIndexed { index, line ->
                    if (index > 0) {
                        val gap = line.top - lines[index - 1].bottom
                        append(if (gap > medianHeight * 0.9) "\n\n" else "\n")
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
            .put("engine", "Tesseract 5 LSTM / tessdata_best rus+eng")
            .put("mean_confidence_percent", meanConfidence)
            .put("page_segmentation_mode", pageSegMode)
            .put("image", JSONObject().put("width_px", width).put("height_px", height))
            .put("coordinate_system", "pixel; origin at top-left; bbox=[left,top,right,bottom]")
            .put("reading_order", "top-to-bottom as detected by Tesseract")
            .put("text", readable)
            .put("lines", jsonLines)

        return buildString {
            append("РАСПОЗНАННЫЙ ТЕКСТ\n")
            append(readable.ifBlank { plain })
            append("\n\nСТРУКТУРА И РАСПОЛОЖЕНИЕ (JSON)\n")
            append("Координаты привязаны к размеру обработанного изображения; слова имеют отдельные рамки.\n")
            append("```json\n")
            append(structure.toString(2))
            append("\n```")
        }
    }

    private fun bbox(left: Int, top: Int, right: Int, bottom: Int) = JSONArray()
        .put(left).put(top).put(right).put(bottom)

    @Suppress("DEPRECATION")
    private fun htmlText(value: String): String =
        Html.fromHtml(value).toString().replace('\u00A0', ' ').trim()
}
