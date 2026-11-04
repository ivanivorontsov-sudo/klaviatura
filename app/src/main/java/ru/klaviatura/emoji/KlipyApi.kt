// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.emoji

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** KLIPY GIF API (api.klipy.com, needs an app key) */
object KlipyApi {
    /** @return pairs (preview jpg/gif url, full gif url) */
    fun query(key: String, q: String): List<Pair<String, String>> {
        val base = "https://api.klipy.com/api/v1/${URLEncoder.encode(key, "UTF-8")}/gifs/"
        val url = if (q.isEmpty()) "${base}trending?per_page=24&customer_id=klaviatura"
            else "${base}search?q=${URLEncoder.encode(q, "UTF-8")}&per_page=24&customer_id=klaviatura&locale=ru"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        val body = c.inputStream.bufferedReader().use { it.readText() }
        return parse(body)
    }

    fun parse(body: String): List<Pair<String, String>> {
        val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i)?.optJSONObject("file") ?: continue
            fun u(size: String, type: String) = f.optJSONObject(size)?.optJSONObject(type)?.optString("url")?.takeIf { it.isNotEmpty() }
            val full = u("md", "gif") ?: u("sm", "gif") ?: u("hd", "gif") ?: continue
            val prev = u("sm", "jpg") ?: u("xs", "jpg") ?: u("sm", "webp") ?: u("sm", "gif") ?: full
            out.add(prev to full)
        }
        return out
    }
}
