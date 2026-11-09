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
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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
 * «Фон с ИИ» (1.4.0: one screen instead of two): prompt, source (Pollinations — free, no login — or a logged-in web chat
 * driven in a WebView), login button, preview and apply. The chat runs in a WebView under the screen; «Показать чат»
 * shows it, so the user can help when a site needs a click (captcha, model choice).
 * Runs in the separate :aibg process so a WebView crash can never take the keyboard down.
 */
class BgGenerateActivity : Activity() {
    private lateinit var promptEdit: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var preview: ImageView
    private lateinit var applyRow: LinearLayout
    private lateinit var source: Spinner
    private lateinit var loginBtn: Button
    private lateinit var showChat: CheckBox
    private lateinit var webBox: LinearLayout
    private var web: WebView? = null
    private var result: File? = null
    private var busy = false
    private var gen = 0 // generation counter: late results of an old run are ignored
    private val seen = HashSet<String>()
    private var candidate: String? = null; private var candidateHits = 0
    private val prefs by lazy { getSharedPreferences("kl_bg_gen", MODE_PRIVATE) }
    private val sources get() = listOf("Pollinations (бесплатно, без входа)") + ChatJs.CHATS.map { "${it.first} (ваш аккаунт)" }

    private fun size(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels to (dm.heightPixels * 0.36f).roundToInt()
    }

    private fun userPrompt() = promptEdit.text.toString().trim().ifEmpty { DEFAULT_PROMPT }

    private fun chatPrompt(): String {
        val (w, h) = size()
        return "Сгенерируй изображение: ${userPrompt()}. Это фон для клавиатуры телефона: широкое горизонтальное изображение " +
            "с соотношением сторон примерно ${"%.1f".format(w.toFloat() / h)}:1, мягкий низкий контраст, без текста и букв."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bg = android.util.TypedValue().also { theme.resolveAttribute(android.R.attr.colorBackground, it, true) }
        outer.setBackgroundColor(bg.data)
        outer.setOnApplyWindowInsetsListener { v, ins ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val t = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                v.setPadding(t.left, t.top, t.right, t.bottom)
            } else @Suppress("DEPRECATION") v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            ins
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24) }
        root.addView(TextView(this).apply { text = "Фон с ИИ"; textSize = 20f })
        promptEdit = EditText(this).apply { hint = "Что нарисовать? Например: северное сияние над тайгой"; minLines = 2
            setText(prefs.getString("prompt", "")) }
        root.addView(promptEdit)
        root.addView(TextView(this).apply { text = "Чем рисовать"; setPadding(0, 16, 0, 0) })
        source = Spinner(this).apply {
            adapter = ArrayAdapter(this@BgGenerateActivity, android.R.layout.simple_spinner_dropdown_item, sources)
            setSelection(prefs.getInt("source", 0).coerceIn(0, sources.size - 1))
        }
        root.addView(source)
        loginBtn = Button(this).apply { isAllCaps = false; setOnClickListener { openLogin() } }
        root.addView(loginBtn)
        showChat = CheckBox(this).apply { text = "Показать чат во время генерации"; isChecked = prefs.getBoolean("show_chat", false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("show_chat", c).apply(); updateWebVisibility() } }
        root.addView(showChat)
        source.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = updateSourceUi()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(Button(this).apply { text = "Сгенерировать"; isAllCaps = false; setOnClickListener { generate(newSeed = false) } })
        bar.addView(Button(this).apply { text = "Ещё вариант"; isAllCaps = false; setOnClickListener { generate(newSeed = true) } })
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
        root.addView(TextView(this).apply { setPadding(0, 12, 0, 0); textSize = 12f
            text = "Размытие и затемнение фона — в «Внешний вид». Для чатов войдите один раз кнопкой выше: вход запоминается." })
        outer.addView(ScrollView(this).apply { addView(root) }, LinearLayout.LayoutParams(-1, 0, 1f))
        webBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        outer.addView(webBox, LinearLayout.LayoutParams(-1, 0, 1.3f))
        setContentView(outer)
        updateSourceUi()
    }

    private fun chat(): Pair<String, String>? = source.selectedItemPosition.let { if (it <= 0) null else ChatJs.CHATS[it - 1] }

    private fun updateSourceUi() {
        val c = chat()
        loginBtn.visibility = if (c == null) View.GONE else View.VISIBLE
        showChat.visibility = loginBtn.visibility
        loginBtn.text = "Войти в ${c?.first ?: ""} / открыть чат"
        updateWebVisibility()
    }

    private fun updateWebVisibility() {
        val w = web
        val visible = showChat.isChecked && chat() != null && w != null
        // a hidden chat stays attached with 1 px height: pages keep running and rendering images
        webBox.visibility = if (w == null) View.GONE else View.VISIBLE
        webBox.layoutParams = (webBox.layoutParams as LinearLayout.LayoutParams).apply {
            height = if (visible) 0 else 1; weight = if (visible) 1.3f else 0f }
        w?.layoutParams = LinearLayout.LayoutParams(-1, if (visible) -1 else 1)
    }

    private fun openLogin() {
        val c = chat() ?: return
        startActivity(Intent(this, AiBackgroundActivity::class.java).putExtra(AiBackgroundActivity.EXTRA_URL, c.second).putExtra(AiBackgroundActivity.EXTRA_NAME, c.first))
    }

    private fun generate(newSeed: Boolean) {
        if (busy && !newSeed) return
        val seed = if (newSeed || prefs.getInt("seed", 0) == 0) (1..999_999).random() else prefs.getInt("seed", 1)
        prefs.edit().putString("prompt", promptEdit.text.toString()).putInt("source", source.selectedItemPosition).putInt("seed", seed).apply()
        busy = true; val run = ++gen
        progress.visibility = View.VISIBLE; applyRow.visibility = View.GONE
        val c = chat()
        if (c == null) pollinations(run, seed) else headless(run, c)
    }

    private fun httpGet(url: String, timeout: Int): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000; readTimeout = timeout; setRequestProperty("User-Agent", "Klaviatura") }

    private fun pollinations(run: Int, seed: Int) {
        val (w, h) = size()
        status.text = "Рисую через Pollinations… обычно 5–30 секунд"
        val user = userPrompt()
        thread(name = "kl-pollinations") {
            try {
                // Pollinations understands English: translate Russian prompts first (falls back to the original text)
                val en = if (ChatJs.hasCyrillic(user)) try {
                    httpGet(ChatJs.translateUrl(user), 30_000).let { c -> if (c.responseCode == 200) c.inputStream.bufferedReader().readText().trim().trim('"').take(400) else null }
                } catch (e: Exception) { null }?.takeIf { it.isNotBlank() && !ChatJs.hasCyrillic(it) } ?: user else user
                runOnUiThread { if (run == gen) status.text = "Рисую: «$en»…" }
                val prompt = "$en. Wide horizontal phone keyboard background, soft low contrast, calm, no text, no letters"
                // a little taller: the bottom strip with the service mark is cut off
                val c = httpGet(ChatJs.pollinationsUrl(prompt, w, (h * 1.12f).roundToInt(), seed), 120_000)
                if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
                val bmp = c.inputStream.use { BitmapFactory.decodeStream(it) } ?: throw Exception("не картинка")
                runOnUiThread { if (run == gen) show(bmp, bottomCut = 0.09f) }
            } catch (e: Throwable) {
                runOnUiThread { if (run == gen) fail("Pollinations не ответил: ${e.message}. Попробуйте ещё раз") }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun headless(run: Int, chat: Pair<String, String>) {
        status.text = "Открываю ${chat.first}…"
        destroyWeb()
        val w = WebView(this)
        web = w
        webBox.addView(w, LinearLayout.LayoutParams(-1, 1))
        updateWebVisibility()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            loadWithOverviewMode = true; useWideViewPort = true
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
        }
        w.addJavascriptInterface(Bridge(run), "KlBridge")
        w.webChromeClient = WebChromeClient()
        var started = false
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                if (started) return; started = true
                w.postDelayed({ if (run == gen) waitForInput(run, w, chat.first, 0) }, 2500)
            }
        }
        w.loadUrl(chat.second)
        w.postDelayed({ if (run == gen && busy) fail("${chat.first} не прислал картинку за 4 минуты. Включите «Показать чат», чтобы посмотреть, что происходит, или выберите Pollinations") }, 240_000)
    }

    /** chat apps render their input late: wait up to ~15 s, then decide login / go */
    private fun waitForInput(run: Int, w: WebView, name: String, attempt: Int) {
        w.evaluateJavascript(ChatJs.STATE) { st ->
            if (run != gen) return@evaluateJavascript
            if (st.contains("login")) {
                if (attempt < 6) { w.postDelayed({ waitForInput(run, w, name, attempt + 1) }, 2500); return@evaluateJavascript }
                fail("Нужно войти в $name: нажмите «Войти в $name», войдите один раз и вернитесь"); return@evaluateJavascript
            }
            w.evaluateJavascript(ChatJs.SCAN) { res ->
                seen.clear(); seen.addAll(ChatJs.parseScan(res).map { it.first }) // everything already on the page is not ours
                w.evaluateJavascript(ChatJs.inject(chatPrompt())) { r ->
                    if (r.contains("noinput")) { fail("Не нашёл поле ввода в $name. Включите «Показать чат» и отправьте запрос вручную"); return@evaluateJavascript }
                    status.text = "Вставил запрос в $name, отправляю…"
                    w.postDelayed({ submit(run, w, name) }, 700)
                }
            }
        }
    }

    private fun submit(run: Int, w: WebView, name: String) {
        w.evaluateJavascript(ChatJs.SUBMIT) { r ->
            if (run != gen) return@evaluateJavascript
            when {
                r.contains("sent") -> { status.text = "Отправил запрос в $name, жду картинку…"; poll(run, w) }
                r.contains("failed") || r.contains("noinput") -> {
                    status.text = "Не получилось нажать «Отправить» в $name. Отправьте вручную в окне чата — картинку заберу сама"
                    if (!showChat.isChecked) showChat.isChecked = true
                    poll(run, w)
                }
                else -> w.postDelayed({ submit(run, w, name) }, 900)
            }
        }
    }

    /** new image must be stable over two scans (chats show blurry previews while drawing) */
    private fun poll(run: Int, w: WebView) {
        w.postDelayed({
            if (run != gen || !busy) return@postDelayed
            w.evaluateJavascript(ChatJs.SCAN) { res ->
                val fresh = ChatJs.parseScan(res).filter { it.first !in seen }
                val pick = fresh.lastOrNull { it.second >= 512 } ?: fresh.lastOrNull()
                if (pick == null) { candidate = null; poll(run, w); return@evaluateJavascript }
                if (pick.first != candidate) { candidate = pick.first; candidateHits = 1; poll(run, w); return@evaluateJavascript }
                if (++candidateHits < (if (pick.second >= 512) 2 else 3)) { poll(run, w); return@evaluateJavascript }
                seen.add(pick.first); candidate = null
                status.text = "Нашёл картинку, забираю…"
                if (pick.first.startsWith("data:")) onData(run, pick.first) else w.evaluateJavascript(ChatJs.fetch(pick.first), null)
            }
        }, 2500)
    }

    inner class Bridge(private val run: Int) {
        @JavascriptInterface fun onData(d: String) { runOnUiThread { onData(run, d) } }
        @JavascriptInterface fun onFail(url: String) { runOnUiThread { if (run == gen) fetchNative(run, url) } }
    }

    /** the page could not read the image (CORS): download it ourselves with the page's cookies */
    private fun fetchNative(run: Int, url: String) {
        val ua = web?.settings?.userAgentString
        val referer = web?.url
        thread {
            val bmp = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 20_000; c.readTimeout = 60_000
                CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
                ua?.let { c.setRequestProperty("User-Agent", it) }; referer?.let { c.setRequestProperty("Referer", it) }
                c.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null }
            runOnUiThread { if (run != gen) return@runOnUiThread
                if (bmp != null) accept(run, bmp) else { status.text = "Не смогла скачать картинку, жду следующую…"; web?.let { poll(run, it) } } }
        }
    }

    private fun onData(run: Int, d: String) {
        if (run != gen) return
        val bytes = try { Base64.decode(d.substringAfter(","), Base64.DEFAULT) } catch (e: Exception) { return }
        accept(run, BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun accept(run: Int, bmp: Bitmap?) {
        // same filters as before: no QR codes, avatars or small icons
        if (bmp == null || !ImageChecks.bigEnough(bmp.width, bmp.height) || BgFx.looksLikeQr(bmp)) { web?.let { poll(run, it) }; return }
        show(bmp)
    }

    private fun show(src: Bitmap, bottomCut: Float = 0f) {
        val (w, h) = size()
        val out = try { ChatJs.cropTo(src, w, h, bottomCut) } catch (e: Throwable) { fail("Не удалось обработать картинку"); return }
        val f = File(cacheDir, "gen_bg.jpg")
        f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        result = f
        preview.setImageBitmap(out)
        busy = false; progress.visibility = View.GONE; applyRow.visibility = View.VISIBLE
        status.text = "Готово: ${w}×$h, ${f.length() / 1024} КБ. Не нравится — «Ещё вариант»"
        destroyWeb()
    }

    private fun destroyWeb() {
        web?.let { it.stopLoading(); (it.parent as? ViewGroup)?.removeView(it); it.destroy() }; web = null
        updateWebVisibility()
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

    override fun onDestroy() { gen++; destroyWeb(); super.onDestroy() }

    companion object { const val DEFAULT_PROMPT = "спокойный абстрактный фон, мягкие тёплые приглушённые тона" }
}
