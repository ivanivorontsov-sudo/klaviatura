// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.code

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Offline-first language-pack store. The catalogue and packs are fetched only on explicit user
 * action; installed files are kept in app-private storage and never sent with typed text.
 */
object SyntaxPackManager {
    private const val BASE = "https://raw.githubusercontent.com/ivanivorontsov-sudo/klaviatura/main/"
    private const val CATALOG = "language-packs/index.json"
    private const val MAX_CATALOG = 512 * 1024
    private const val MAX_PACK = 40 * 1024 * 1024
    private const val MAX_REDIRECTS = 5

    data class Pack(
        val id: String,
        val name: String,
        val language: String,
        val type: String,
        val path: String,
        val description: String,
        val sha256: String
    )

    @Volatile private var appContext: Context? = null
    private const val PREFS = "kl_language_packs"
    private const val PREF_ACTIVE_SYNTAX = "active_syntax"
    fun init(context: Context) { appContext = context.applicationContext }

    fun activeSyntax(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_ACTIVE_SYNTAX, null)

    fun setActiveSyntax(context: Context, id: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_ACTIVE_SYNTAX, id).apply()
    }

    private fun root(context: Context) = File(context.filesDir, "language-packs").apply { mkdirs() }
    private fun installedFile(context: Context, pack: Pack) =
        File(root(context), "${pack.id}.${if (pack.type == "lexicon") "tsv" else "json"}")

    fun catalog(): List<Pack> {
        val json = JSONObject(String(readUrl(BASE + CATALOG, MAX_CATALOG), Charsets.UTF_8))
        val array = json.optJSONArray("packs") ?: JSONArray()
        val out = ArrayList<Pack>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val type = o.optString("type")
            val path = o.optString("path")
            if (!id.matches(Regex("[a-z0-9_-]{2,48}"))) continue
            if (type !in setOf("syntax", "lexicon")) continue
            val validPath = path.startsWith("language-packs/") || (type == "lexicon" && path.matches(Regex("app/src/main/assets/glide/[a-z]{2,3}\\.tsv")))
            if (!validPath || path.contains("..")) continue
            out.add(Pack(id, o.optString("name", id), o.optString("language"), type, path,
                o.optString("description"), o.optString("sha256")))
        }
        return out
    }

    fun installed(context: Context): Set<String> = root(context).listFiles()
        ?.mapNotNull { it.name.substringBeforeLast('.') }?.toSet().orEmpty()

    fun install(context: Context, pack: Pack) {
        val validPath = pack.path.startsWith("language-packs/") || (pack.type == "lexicon" && pack.path.matches(Regex("app/src/main/assets/glide/[a-z]{2,3}\\.tsv")))
        require(validPath && !pack.path.contains("..")) { "Некорректный путь пакета" }
        val bytes = readUrl(BASE + pack.path, MAX_PACK)
        if (pack.sha256.isNotBlank()) {
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (!actual.equals(pack.sha256, true)) throw IOException("Контрольная сумма пакета не совпала")
        }
        if (pack.type == "syntax") validateSyntax(bytes) else validateLexicon(bytes)
        val target = installedFile(context, pack)
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        if (!target.isFile || target.length() != bytes.size.toLong()) throw IOException("Не удалось сохранить пакет")
    }

    fun remove(context: Context, pack: Pack) { installedFile(context, pack).delete() }

    private fun validateSyntax(bytes: ByteArray) {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.optString("language").isNotBlank()) { "В пакете не указан язык" }
        require(json.optJSONObject("keywords") != null || json.optJSONObject("phrases") != null) {
            "В пакете нет словаря конструкций"
        }
    }

    private fun validateLexicon(bytes: ByteArray) {
        val text = String(bytes, Charsets.UTF_8)
        require(text.lineSequence().take(20).any { it.contains('\t') }) { "Это не словарь формата TSV" }
        require(text.length > 20) { "Пустой словарь" }
    }

    /** Combined spoken aliases for every installed syntax package. */
    fun spokenPhrases(): Map<String, String> {
        val context = appContext ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        val active = activeSyntax(context) ?: return emptyMap()
        root(context).listFiles()?.filter { it.extension == "json" && it.nameWithoutExtension == active }?.forEach { file ->
            runCatching {
                val json = JSONObject(file.readText())
                val phrases = json.optJSONObject("phrases") ?: return@runCatching
                val keys = phrases.keys()
                while (keys.hasNext()) {
                    val key = keys.next().lowercase(Locale.ROOT).replace('ё', 'е').trim()
                    val value = phrases.optString(key)
                    if (key.isNotBlank() && value.isNotBlank() && key.length <= 80 && value.length <= 80)
                        result[key] = value
                }
            }
        }
        return result
    }

    fun spokenKeywords(): Map<String, String> {
        val context = appContext ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        root(context).listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            runCatching {
                val json = JSONObject(file.readText())
                val keywords = json.optJSONObject("keywords") ?: return@runCatching
                val keys = keywords.keys()
                while (keys.hasNext()) {
                    val key = keys.next().lowercase(Locale.ROOT).replace('ё', 'е').trim()
                    val value = keywords.optString(key)
                    if (key.isNotBlank() && value.isNotBlank() && key.length <= 64 && value.length <= 64)
                        result[key] = value
                }
            }
        }
        return result
    }

    /** Merge installed offline word packs into the in-memory swipe lexicon. */
    fun applyLexicons(language: String, lexicon: Lexicon) {
        val context = appContext ?: return
        root(context).listFiles()?.filter { it.extension == "tsv" }?.forEach { file ->
            if (!file.nameWithoutExtension.endsWith("_$language") &&
                !file.nameWithoutExtension.endsWith("-$language") &&
                file.nameWithoutExtension != language) return@forEach
            runCatching {
                file.useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0) lexicon.addOrBoost(line.substring(0, tab), line.substring(tab + 1).toLongOrNull() ?: 1L)
                    }
                }
            }
        }
    }

    private fun readUrl(url: String, maxBytes: Int): ByteArray {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 90_000
            connection.setRequestProperty("User-Agent", "Klaviatura-LanguagePacks/1.0")
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: throw IOException("Редирект без адреса")
                val next = URL(URL(current), location)
                if (next.protocol != "https") throw IOException("Разрешены только HTTPS-загрузки")
                current = next.toString()
                connection.disconnect()
                return@repeat
            }
            if (code !in 200..299) throw IOException("Сервер ответил HTTP $code")
            val size = connection.contentLengthLong
            if (size > maxBytes) throw IOException("Пакет слишком большой")
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) throw IOException("Превышен лимит размера пакета")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }.also { connection.disconnect() }
        }
        throw IOException("Слишком много перенаправлений")
    }
}
