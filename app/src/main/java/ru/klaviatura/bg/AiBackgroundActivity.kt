// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.bg

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 1.4.0: login window for a web chat used by «Фон с ИИ» (BgGenerateActivity). The user logs in once;
 * cookies are kept, the generation itself runs from the «Фон с ИИ» screen.
 */
class AiBackgroundActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: ChatJs.CHATS[0].second
        val name = intent.getStringExtra(EXTRA_NAME) ?: ChatJs.CHATS.firstOrNull { it.second == url }?.first ?: "чат"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { v, ins ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val t = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                v.setPadding(t.left, t.top, t.right, t.bottom)
            } else @Suppress("DEPRECATION") v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            ins
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(24, 8, 16, 8) }
        bar.addView(TextView(this).apply { text = "Вход в $name: войдите и нажмите «Готово»"; textSize = 15f }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Button(this).apply { text = "Готово"; isAllCaps = false; setOnClickListener { CookieManager.getInstance().flush(); finish() } })
        root.addView(bar)
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
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { CookieManager.getInstance().flush() }
        }
        web.loadUrl(url)
    }

    override fun onDestroy() { CookieManager.getInstance().flush(); web.destroy(); super.onDestroy() }

    companion object {
        const val ACTION_BG_CHANGED = "ru.klaviatura.BG_CHANGED"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
}
