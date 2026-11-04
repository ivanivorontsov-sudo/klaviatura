// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.bg

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.latin.settings.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Settings → background → generate from a text prompt.
 * Sources: Pollinations (free, no key, default) or a logged-in web chat driven headlessly in a hidden WebView.
 * Runs in the separate :aibg process so a WebView crash can never take the keyboard down.
 */
class BgGenerateActivity : Activity() {
    private lateinit var promptEdit: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var preview: ImageView
    private lateinit var applyRow: LinearLayout
    private lateinit var source: Spinner
    private var web: WebView? = null
    private var result: File? = null
    private var busy = false
    private var gen = 0 // generation counter: late results of an old run are ignored
    private val seen = HashSet<String>()
    private val prefs by lazy { getSharedPreferences("kl_bg_gen", MODE_PRIVATE) }
    private val sources get() = listOf("Pollinations (бесплатно, без входа)") + ChatJs.CHATS.map { "${it.first} (веб-чат, нужен вход)" }

    private fun size(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels to (dm.heightPixels * 0.36f).roundToInt()
    }

    private fun fullPrompt(): String {
        val user = promptEdit.text.toString().trim().ifEmpty { DEFAULT_PROMPT }
        val (w, h) = size()
        return "$user. Фон для клавиатуры телефона, широкое горизонтальное изображение ${w}:${h}, мягкий низкий контраст, без текста и букв."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frame = FrameLayout(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24) }
        frame.setOnApplyWindowInsetsListener { v, ins ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val t = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                v.setPadding(t.left, t.top, t.right, t.bottom)
            } else @Suppress("DEPRECATION") v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            ins
        }
        root.addView(TextView(this).apply { text = "Фон по описанию"; textSize = 20f })
        root.addView(TextView(this).apply { text = "Источник" ; setPadding(0, 16, 0, 0) })
        source = Spinner(this).apply {
            adapter = ArrayAdapter(this@BgGenerateActivity, android.R.layout.simple_spinner_dropdown_item, sources)
            setSelection(prefs.getInt("source", 0).coerceIn(0, sources.size - 1))
        }
        root.addView(source)
        promptEdit = EditText(this).apply { hint = "Например: северное сияние над тайгой"; minLines = 2
            setText(prefs.getString("prompt", "")) }
        root.addView(promptEdit)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(Button(this).apply { text = "Сгенерировать"; isAllCaps = false; setOnClickListener { generate(newSeed = false) } })
        bar.addView(Button(this).apply { text = "Ещё вариант"; isAllCaps = false; setOnClickListener { generate(newSeed = true) } })
        bar.addView(Button(this).apply { text = "Вход в чат"; isAllCaps = false
            setOnClickListener { startActivity(Intent(this@BgGenerateActivity, AiBackgroundActivity::class.java)) } })
        root.addView(bar)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = View.GONE }
        root.addView(progress)
        status = TextView(this).apply { setPadding(0, 8, 0, 8) }
        root.addView(status)
        preview = ImageView(this).apply { adjustViewBounds = true }
        root.addView(preview)
        applyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        applyRow.addView(Button(this).apply { text = "Применить"; isAllCaps = false; setOnClickListener { apply(true, true) } })
        applyRow.addView(Button(this).apply { text = "Только светлая"; isAllCaps = false; setOnClickListener { apply(true, false) } })
        applyRow.addView(Button(this).apply { text = "Только тёмная"; isAllCaps = false; setOnClickListener { apply(false, true) } })
        root.addView(applyRow)
        frame.addView(ScrollView(this).apply { addView(root) })
        setContentView(frame)
    }

    private fun generate(newSeed: Boolean) {
        if (busy && !newSeed) return
        val seed = if (newSeed || prefs.getInt("seed", 0) == 0) (1..999_999).random() else prefs.getInt("seed", 1)
        prefs.edit().putString("prompt", promptEdit.text.toString()).putInt("source", source.selectedItemPosition).putInt("seed", seed).apply()
        busy = true; val run = ++gen
        progress.visibility = View.VISIBLE; applyRow.visibility = View.GONE
        if (source.selectedItemPosition == 0) pollinations(run, seed) else headless(run, ChatJs.CHATS[source.selectedItemPosition - 1])
    }

    private fun pollinations(run: Int, seed: Int) {
        val (w, h) = size()
        status.text = "Рисую через Pollinations… обычно 5–30 секунд"
        val url = ChatJs.pollinationsUrl(fullPrompt(), w, h, seed)
        thread(name = "kl-pollinations") {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 20_000; c.readTimeout = 120_000
                c.setRequestProperty("User-Agent", "Klaviatura")
                if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
                val bmp = c.inputStream.use { BitmapFactory.decodeStream(it) } ?: throw Exception("не картинка")
                runOnUiThread { if (run == gen) show(bmp) }
            } catch (e: Throwable) {
                runOnUiThread { if (run == gen) fail("Pollinations не ответил: ${e.message}. Попробуйте ещё раз") }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun headless(run: Int, chat: Pair<String, String>) {
        status.text = "Открываю ${chat.first} в фоне…"
        web?.destroy()
        val w = WebView(this)
        web = w
        // hidden but laid out at a real size, so chat pages render and run normally
        (window.decorView as ViewGroup).addView(w, 0, ViewGroup.LayoutParams(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels))
        w.visibility = View.INVISIBLE
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
        }
        w.addJavascriptInterface(Bridge(run), "KlBridge")
        var started = false
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (started) return; started = true
                w.postDelayed({ if (run == gen) step1(run, w, chat.first) }, 4000)
            }
        }
        w.loadUrl(chat.second)
        w.postDelayed({ if (run == gen && busy) fail("${chat.first} не прислал картинку за 3 минуты. Попробуйте ещё раз или Pollinations") }, 180_000)
    }

    private fun step1(run: Int, w: WebView, name: String) {
        w.evaluateJavascript(ChatJs.STATE) { st ->
            if (run != gen) return@evaluateJavascript
            if (st.contains("login")) { fail("Нужно войти в $name: нажмите «Вход в чат», войдите один раз, затем вернитесь"); return@evaluateJavascript }
            w.evaluateJavascript(ChatJs.SCAN) { res ->
                seen.clear(); seen.addAll(parse(res)) // everything already on the page is not ours
                w.evaluateJavascript(ChatJs.inject("Сгенерируй изображение: " + fullPrompt(), submit = true)) { r ->
                    if (r.contains("noinput")) { fail("Не нашёл поле ввода в $name"); return@evaluateJavascript }
                    status.text = "Отправил запрос в $name, жду картинку…"
                    poll(run, w)
                }
            }
        }
    }

    private fun poll(run: Int, w: WebView) {
        w.postDelayed({
            if (run != gen || !busy) return@postDelayed
            w.evaluateJavascript(ChatJs.SCAN) { res ->
                val pick = parse(res).lastOrNull { it !in seen }
                if (pick == null) { poll(run, w); return@evaluateJavascript }
                seen.add(pick)
                if (pick.startsWith("data:")) onData(run, pick) else w.evaluateJavascript(ChatJs.fetch(pick), null)
            }
        }, 3000)
    }

    private fun parse(res: String?): List<String> = try {
        val arr = org.json.JSONArray(org.json.JSONTokener(res).nextValue() as String)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) { emptyList() }

    inner class Bridge(private val run: Int) {
        @JavascriptInterface fun onData(d: String) { runOnUiThread { onData(run, d) } }
        @JavascriptInterface fun onFail(url: String) { runOnUiThread { if (run == gen) web?.let { poll(run, it) } } }
    }

    private fun onData(run: Int, d: String) {
        if (run != gen) return
        val bytes = try { Base64.decode(d.substringAfter(","), Base64.DEFAULT) } catch (e: Exception) { return }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        // same filters as the visible capture: no QR codes, avatars or small icons
        if (bmp == null || !ImageChecks.bigEnough(bmp.width, bmp.height) || BgFx.looksLikeQr(bmp)) { web?.let { poll(run, it) }; return }
        show(bmp)
    }

    private fun show(src: Bitmap) {
        val (w, h) = size()
        val out = try { ChatJs.cropTo(src, w, h) } catch (e: Throwable) { fail("Не удалось обработать картинку"); return }
        val f = File(cacheDir, "gen_bg.jpg")
        f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        result = f
        preview.setImageBitmap(out)
        busy = false; progress.visibility = View.GONE; applyRow.visibility = View.VISIBLE
        status.text = "Готово: ${w}×$h, ${f.length() / 1024} КБ. Не нравится — «Ещё вариант»"
        web?.let { it.stopLoading(); (it.parent as? ViewGroup)?.removeView(it); it.destroy() }; web = null
    }

    private fun fail(msg: String) {
        busy = false; progress.visibility = View.GONE; status.text = msg
    }

    private fun apply(day: Boolean, night: Boolean) {
        val f = result ?: return
        if (day) f.copyTo(Settings.getCustomBackgroundFile(this, false, false), true)
        if (night) f.copyTo(Settings.getCustomBackgroundFile(this, true, false), true)
        Settings.clearCachedBackgroundImages()
        sendBroadcast(Intent(AiBackgroundActivity.ACTION_BG_CHANGED).setPackage(packageName))
        Toast.makeText(this, "Фон применён", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() { gen++; web?.destroy(); super.onDestroy() }

    companion object { const val DEFAULT_PROMPT = "спокойный абстрактный фон, мягкие тёплые приглушённые тона" }
}
