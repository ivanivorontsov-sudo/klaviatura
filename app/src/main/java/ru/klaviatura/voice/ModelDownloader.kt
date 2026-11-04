// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.voice

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** pure-JVM model download/install (unit-tested without Android). Tries mirrors in order. */
object ModelDownloader {
    val MIRRORS: Map<String, List<String>> = mapOf(
        "ru" to listOf(
            "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
            "https://huggingface.co/localstack/vosk-models/resolve/main/vosk-model-small-ru-0.22.zip",
            "https://huggingface.co/rhasspy/vosk-models/resolve/main/ru/vosk-model-small-ru-0.22.zip"),
        "en" to listOf(
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            "https://huggingface.co/localstack/vosk-models/resolve/main/vosk-model-small-en-us-0.15.zip",
            "https://huggingface.co/rhasspy/vosk-models/resolve/main/en/vosk-model-small-en-us-0.15.zip"),
    )

    /** sherpa-onnx streaming Zipformer (Vosk small-streaming-ru, Apache-2.0), int8, ~29 MB: plain files, no zip */
    const val ZIP_RU = "ru-z"
    val FILE_MODELS: Map<String, Pair<List<String>, List<String>>> = mapOf(
        ZIP_RU to (listOf(
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/resolve/main/",
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/resolve/main/")
            to listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")))

    fun isFileModelDir(lang: String, dir: File) = FILE_MODELS[lang]?.second?.all { File(dir, it).isFile } == true

    /** downloads every file of a [FILE_MODELS] model, progress over the sum of all files */
    fun downloadFiles(lang: String, dest: File, l: Listener) {
        val (bases, files) = FILE_MODELS[lang] ?: throw IOException("нет модели $lang")
        val errors = ArrayList<String>()
        val tmp = File(dest.parentFile, dest.name + ".part"); 
        for ((i, base) in bases.withIndex()) {
            try {
                tmp.deleteRecursively(); tmp.mkdirs()
                val conns = files.map { open(base + it) }
                val total = conns.sumOf { it.contentLengthLong.coerceAtLeast(0) }
                var done = 0L
                for ((f, c) in files.zip(conns)) {
                    val exp = c.contentLengthLong; var got = 0L
                    c.inputStream.use { inp -> File(tmp, f).outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { val r = inp.read(buf); if (r < 0) break; out.write(buf, 0, r); got += r; done += r; l.onProgress(done, total, i) }
                    } }
                    if (exp > 0 && got != exp) throw IOException("загрузка оборвалась ($f)")
                }
                dest.deleteRecursively()
                if (!tmp.renameTo(dest)) { tmp.copyRecursively(dest, true); tmp.deleteRecursively() }
                return
            } catch (e: Exception) {
                tmp.deleteRecursively(); val msg = humanError(e); errors.add("${URL(base).host}: $msg"); l.onMirrorFailed(base, msg)
            }
        }
        throw IOException(errors.joinToString("; "))
    }

    interface Listener {
        fun onProgress(done: Long, total: Long, mirror: Int) {}
        fun onUnzip() {}
        fun onMirrorFailed(url: String, error: String) {}
    }

    fun isModelDir(dir: File) = File(dir, "am").isDirectory && File(dir, "conf").isDirectory

    /** follows redirects manually (HttpURLConnection does not follow cross-host / protocol redirects reliably) */
    fun open(url: String, maxRedirects: Int = 8): HttpURLConnection {
        var u = url
        repeat(maxRedirects) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000; c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "Klaviatura/1.2 (Android)")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("редирект без адреса")
                u = URL(URL(u), loc).toString(); c.disconnect(); return@repeat
            }
            if (code !in 200..299) throw IOException("сервер ответил $code")
            return c
        }
        throw IOException("слишком много редиректов")
    }

    /** downloads into [dest] (model dir) trying all mirrors; throws with all errors if every mirror failed */
    fun download(lang: String, dest: File, l: Listener, urls: List<String> = MIRRORS[lang] ?: emptyList()) {
        if (urls.isEmpty()) throw IOException("нет модели для языка $lang")
        val errors = ArrayList<String>()
        val zip = File(dest.parentFile, "$lang.zip")
        dest.parentFile?.mkdirs()
        for ((i, url) in urls.withIndex()) {
            try {
                val c = open(url)
                val total = c.contentLengthLong
                var done = 0L
                c.inputStream.use { inp -> zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = inp.read(buf); if (r < 0) break
                        out.write(buf, 0, r); done += r
                        l.onProgress(done, total, i)
                    }
                } }
                if (total > 0 && done != total) throw IOException("загрузка оборвалась (${done / 1048576} из ${total / 1048576} МБ)")
                l.onUnzip()
                zip.inputStream().use { install(it, dest) }
                zip.delete()
                return
            } catch (e: Exception) {
                zip.delete()
                val msg = humanError(e)
                errors.add("${URL(url).host}: $msg"); l.onMirrorFailed(url, msg)
            }
        }
        throw IOException(errors.joinToString("; "))
    }

    fun humanError(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "нет интернета или сайт недоступен"
        is java.net.SocketTimeoutException -> "сервер не отвечает"
        is javax.net.ssl.SSLException -> "ошибка защищённого соединения"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** installs a model zip (from download or from a file the user picked); handles zips with or without top folder */
    fun install(zipStream: InputStream, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.deleteRecursively(); tmp.mkdirs()
        try {
            val entries = ArrayList<String>()
            ZipInputStream(zipStream.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name.contains("..")) continue
                    val f = File(tmp, e.name)
                    entries.add(e.name)
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
                }
            }
            // find the folder that contains am/ and conf/
            val root = if (isModelDir(tmp)) tmp else tmp.walkTopDown().maxDepth(3).firstOrNull { it.isDirectory && isModelDir(it) }
                ?: throw IOException("в архиве нет модели Vosk (нужны папки am и conf)")
            dest.deleteRecursively()
            if (!root.renameTo(dest)) { root.copyRecursively(dest, true) }
        } finally { tmp.deleteRecursively() }
    }
}
