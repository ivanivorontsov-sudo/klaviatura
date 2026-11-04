// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.bg

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.settings.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * AI keyboard background without any API: the user's normal web chat (logged in once, cookies kept)
 * runs inside a WebView. We put a prompt into the clipboard / input, watch the page for newly generated
 * large images, grab ("eat") the image, crop it to the keyboard's aspect ratio, compress it and show a preview.
 */
class AiBackgroundActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private val seen = HashSet<String>()
    private var dialogShown = false

    private val chats = listOf(
        "Qwen" to "https://chat.qwen.ai/",
        "ChatGPT" to "https://chatgpt.com/",
        "Gemini" to "https://gemini.google.com/app",
        "Grok" to "https://grok.com/",
        "Алиса" to "https://alice.yandex.ru/",
        "Copilot" to "https://copilot.microsoft.com/",
        "Шедеврум" to "https://shedevrum.ai/",
    )

    private fun ratio(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val w = dm.widthPixels; val h = (dm.heightPixels * 0.36f).roundToInt()
        return w to h
    }

    private fun prompt(): String {
        val (w, h) = ratio()
        return "Сгенерируй изображение: спокойный абстрактный фон для клавиатуры телефона, соотношение сторон ${w}:${h} " +
            "(широкое горизонтальное, примерно ${"%.1f".format(w.toFloat() / h)}:1). Мягкие тёплые приглушённые тона, " +
            "низкий контраст, без текста, без букв, без мелких деталей и ярких пятен, приятно для глаз в тёмной и светлой теме."
    }

    /** images may be captured only after the prompt was sent (armed), never on login / start pages */
    private var armed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // edge-to-edge (targetSdk 35): keep our content below the status bar and above the IME / nav bar
        root.setOnApplyWindowInsetsListener { v, ins ->
            val t = if (android.os.Build.VERSION.SDK_INT >= 30)
                ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            else null
            if (t != null) v.setPadding(t.left, t.top, t.right, t.bottom)
            else @Suppress("DEPRECATION") v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            ins
        }
        root.addView(TextView(this).apply { text = "ИИ-фон"; textSize = 20f; setPadding(24, 16, 24, 4) })
        // clear site chooser
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(24, 0, 24, 0) }
        row.addView(TextView(this).apply { text = "Нейросеть: " })
        val spinner = android.widget.Spinner(this)
        spinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, chats.map { it.first })
        val last = getPreferences(MODE_PRIVATE).getString("last", chats[0].second)
        spinner.setSelection(chats.indexOfFirst { it.second == last }.coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (chats[pos].second != getPreferences(MODE_PRIVATE).getString("last", null)) open(chats[pos].second)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        row.addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16, 0, 16, 0) }
        bar.addView(Button(this).apply { text = "Промпт"; isAllCaps = false; setOnClickListener { copyPrompt(); injectPrompt(); arm() } })
        bar.addView(Button(this).apply { text = "Взять картинку"; isAllCaps = false; setOnClickListener { scan(force = true) } })
        root.addView(bar)
        status = TextView(this).apply { setPadding(24, 4, 24, 8); gravity = Gravity.START
            text = "Выберите нейросеть, войдите (вход запомнится), модель выберите в меню самого чата. Нажмите «Промпт» и отправьте — картинка из ответа подхватится сама." }
        root.addView(status)
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true; setSupportMultipleWindows(false)
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
        }
        web.addJavascriptInterface(Bridge(), "KlBridge")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { CookieManager.getInstance().flush() }
        }
        web.setDownloadListener { url, ua, _, _, _ -> if (url.startsWith("data:")) handleDataUrl(url) else fetchNative(url, ua) }
        open(getPreferences(MODE_PRIVATE).getString("last", chats[0].second)!!)
        web.postDelayed(object : Runnable { override fun run() { if (!isFinishing) { if (armed) scan(false); web.postDelayed(this, 2500) } } }, 4000)
    }

    private fun open(url: String) { armed = false; getPreferences(MODE_PRIVATE).edit().putString("last", url).apply(); seen.clear(); web.loadUrl(url)
        web.postDelayed({ scan(false, markOnly = true) }, 3500) }

    /** remember everything visible now (login QR, logos, old answers) so only images of the new answer count */
    private fun arm() { scan(false, markOnly = true); armed = true }

    private fun copyPrompt() {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("prompt", prompt()))
        Toast.makeText(this, "Промпт скопирован. Если не вставился — вставьте и отправьте", Toast.LENGTH_SHORT).show()
    }

    private fun injectPrompt() {
        val p = org.json.JSONObject.quote(prompt())
        web.evaluateJavascript("""(function(){var p=$p;var el=document.querySelector('textarea')||document.querySelector('[contenteditable=true]');
            if(!el)return;el.focus();if(el.tagName==='TEXTAREA'){var s=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set;s.call(el,p);
            el.dispatchEvent(new Event('input',{bubbles:true}));}else{document.execCommand('insertText',false,p);}})()""", null)
    }

    /** looks for large images; new ones are fetched inside the page (with its cookies) and passed back as data URL */
    private fun scan(force: Boolean, markOnly: Boolean = false) {
        web.evaluateJavascript("""(function(){var r=[];document.querySelectorAll('img').forEach(function(i){
            var w=i.naturalWidth,h=i.naturalHeight,s=i.currentSrc||i.src,a=(i.alt||'')+' '+(i.className||'');
            if(Math.min(w,h)<512)return; if(/qr|avatar|logo|icon|favicon|emoji|sprite|badge|profile|login|captcha/i.test(s.split('?')[0].slice(-120)+' '+a))return;
            if(i.closest('header,nav,button,[role=dialog],[class*=login],[class*=Login],[class*=qr],[class*=QR],[class*=avatar]'))return;
            var r2=i.getBoundingClientRect(); if(r2.width<200)return; r.push(s);});
            var login=!!document.querySelector('input[type=password]')||!document.querySelector('textarea,[contenteditable=true]');
            return JSON.stringify(login?[]:r);})()""") { res ->
            val arr = try { org.json.JSONArray(org.json.JSONTokener(res).nextValue() as String) } catch (e: Exception) { return@evaluateJavascript }
            val urls = (0 until arr.length()).map { arr.getString(it) }
            if (markOnly) { seen.addAll(urls); return@evaluateJavascript }
            val new = urls.filter { it !in seen }
            val pick = new.lastOrNull() ?: (if (force) urls.lastOrNull() else null)
            if (pick == null) { if (force) Toast.makeText(this, "Картинок пока нет", Toast.LENGTH_SHORT).show(); return@evaluateJavascript }
            seen.addAll(urls)
            status.text = "Нашёл картинку, забираю…"
            if (pick.startsWith("data:")) { handleDataUrl(pick); return@evaluateJavascript }
            val u = org.json.JSONObject.quote(pick)
            web.evaluateJavascript("""(function(){fetch($u,{credentials:'include'}).then(function(r){return r.blob();}).then(function(b){
                var f=new FileReader();f.onload=function(){KlBridge.onData(f.result);};f.readAsDataURL(b);})
                .catch(function(e){KlBridge.onFail($u);});})()""", null)
        }
    }

    inner class Bridge {
        @JavascriptInterface fun onData(d: String) { runOnUiThread { handleDataUrl(d) } }
        @JavascriptInterface fun onFail(url: String) { runOnUiThread { fetchNative(url, web.settings.userAgentString) } }
    }

    private fun handleDataUrl(d: String) {
        val bytes = try { Base64.decode(d.substringAfter(","), Base64.DEFAULT) } catch (e: Exception) { return }
        process(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun fetchNative(url: String, ua: String?) {
        thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
                ua?.let { c.setRequestProperty("User-Agent", it) }
                val bmp = c.inputStream.use { BitmapFactory.decodeStream(it) }
                runOnUiThread { process(bmp) }
            } catch (e: Exception) { runOnUiThread { status.text = "Не удалось забрать картинку: ${e.message}" } }
        }
    }

    /** crop to keyboard ratio, scale to screen width, compress, preview */
    private fun process(src: Bitmap?) {
        if (src == null || dialogShown) return
        if (!ImageChecks.bigEnough(src.width, src.height) || BgFx.looksLikeQr(src)) {
            status.text = "Пропустил служебную картинку (QR/иконка). Жду картинку из ответа…"; return
        }
        val (w, h) = ratio()
        val target = w.toFloat() / h
        var cw = src.width; var ch = (cw / target).roundToInt()
        if (ch > src.height) { ch = src.height; cw = (ch * target).roundToInt() }
        val cropped = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
        val scaled = Bitmap.createScaledBitmap(cropped, w, h, true)
        val tmp = File(cacheDir, "ai_bg.jpg")
        tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        val kb = tmp.length() / 1024
        status.text = "Картинка готова: ${w}×$h, $kb КБ"
        dialogShown = true
        AlertDialog.Builder(this)
            .setTitle("Применить фон? (${w}×$h, $kb КБ)")
            .setView(ImageView(this).apply { setImageBitmap(scaled); adjustViewBounds = true; setPadding(16, 16, 16, 16) })
            .setPositiveButton("Обе темы") { _, _ -> apply(tmp, true, true) }
            .setNeutralButton("Только светлая") { _, _ -> apply(tmp, true, false) }
            .setNegativeButton("Только тёмная") { _, _ -> apply(tmp, false, true) }
            .setOnDismissListener { dialogShown = false }
            .show()
    }

    private fun apply(f: File, day: Boolean, night: Boolean) {
        if (day) f.copyTo(Settings.getCustomBackgroundFile(this, false, false), true)
        if (night) f.copyTo(Settings.getCustomBackgroundFile(this, true, false), true)
        Settings.clearCachedBackgroundImages()
        try { KeyboardSwitcher.getInstance().setThemeNeedsReload() } catch (e: Exception) { }
        // the keyboard lives in the main process: tell it to reload (and the cache checks file mtime anyway)
        sendBroadcast(android.content.Intent(ACTION_BG_CHANGED).setPackage(packageName))
        armed = false
        Toast.makeText(this, "Фон применён. Размытие и затемнение — в настройках внешнего вида", Toast.LENGTH_LONG).show()
    }

    companion object { const val ACTION_BG_CHANGED = "ru.klaviatura.BG_CHANGED" }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
}
