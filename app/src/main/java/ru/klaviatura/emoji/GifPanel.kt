// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.emoji

import android.content.ClipDescription
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.viewpager2.widget.ViewPager2
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs
import java.io.File
import java.util.concurrent.Executors

/**
 * GIF tab in the emoji palette: a "GIF" chip over the emoji pages opens a panel with KLIPY search results
 * (query = the word before the cursor, or quick chips / trending). Tapping a GIF downloads it and inserts it
 * with commitContent (or puts it into the clipboard when the field doesn't accept images).
 * KLIPY needs an app key: from the build (CI secret KLIPY_KEY) or entered by the user in settings (pref kl_gif_key).
 */
object GifPanel {
    const val PREF_KEY = "kl_gif_key"
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private data class Sticker(val id: String, val title: String, val tags: String)
    private val QUICK = listOf(
        "привет" to "привет", "ура" to "ура", "обнять" to "обнять", "не знаю" to "не знаю"
    )
    // Only verified bundled assets are exposed in this initial offline pack.
    private val STICKERS = listOf(
        Sticker("hello", "Привет!", "привет здравствуй хай"),
        Sticker("hooray", "Ура!", "ура победа отлично"),
        Sticker("hug", "Обнимаю", "обнять обнимаю поддержка"),
        Sticker("shrug", "Не знаю", "не знаю хз")
    )
    private val stickerLock = Any()
    private fun stickerDir(ctx: Context): File = synchronized(stickerLock) {
        val dir = File(ctx.filesDir, "gif_stickers").apply { mkdirs() }
        STICKERS.forEach { sticker ->
            val target = File(dir, "${sticker.id}.gif")
            if (!target.isFile) {
                ctx.assets.open("gif_stickers/${sticker.id}.gif").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        dir
    }

    fun key(ctx: Context): String = ctx.prefs().getString(PREF_KEY, null)?.trim()?.takeIf { it.isNotEmpty() } ?: BuildConfig.KLIPY_KEY

    fun attach(root: View, pager: ViewPager2, listener: KeyboardActionListener) {
        val tagged = pager.getTag(R.id.emoji_pager) as? Holder
        if (tagged != null) { tagged.listener = listener; tagged.hide(); return }
        val parent = pager.parent as? ViewGroup ?: return
        val idx = parent.indexOfChild(pager)
        val lp = pager.layoutParams
        parent.removeView(pager)
        val frame = FrameLayout(root.context)
        parent.addView(frame, idx, lp)
        frame.addView(pager, FrameLayout.LayoutParams(-1, -1))
        val h = Holder(frame, pager, listener)
        pager.setTag(R.id.emoji_pager, h)
    }

    private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun ime(ctx: Context): InputMethodService? {
        var c: Context? = ctx
        while (c != null) { if (c is InputMethodService) return c; c = (c as? ContextWrapper)?.baseContext }
        return null
    }

    private class Holder(val frame: FrameLayout, val pager: ViewPager2, var listener: KeyboardActionListener) {
        val ctx: Context = frame.context
        val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setBackgroundColor(0xF0202020.toInt()) }
        val grid = GridLayout(ctx).apply { columnCount = 3 }
        val status = TextView(ctx).apply { setTextColor(Color.LTGRAY); setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8)) }
        val chip = TextView(ctx).apply {
            text = "GIF"; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = ctx.dp(14).toFloat(); setColor(0xCC5C6BC0.toInt()) }
            setPadding(ctx.dp(12), ctx.dp(4), ctx.dp(12), ctx.dp(4))
        }
        var gen = 0

        init {
            val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            fun addChip(label: String, act: () -> Unit) = chips.addView(TextView(ctx).apply {
                text = label; setTextColor(Color.WHITE); textSize = 14f; setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6)); setOnClickListener { act() }
            })
            addChip("✕") { hide() }
            addChip("Все") { search("") }
            addChip("по слову") { search(wordBeforeCursor()) }
            QUICK.forEach { (l, q) -> addChip(l) { search(q) } }
            panel.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
            panel.addView(status)
            panel.addView(ScrollView(ctx).apply { addView(grid) }, LinearLayout.LayoutParams(-1, 0, 1f))
            frame.addView(panel, FrameLayout.LayoutParams(-1, -1))
            frame.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, ctx.dp(4), ctx.dp(6), 0) })
            chip.setOnClickListener { if (panel.visibility == View.VISIBLE) hide() else show() }
        }

        fun hide() { panel.visibility = View.GONE; pager.visibility = View.VISIBLE; chip.text = "GIF" }
        fun show() { panel.visibility = View.VISIBLE; pager.visibility = View.INVISIBLE; chip.text = "😀"; search(wordBeforeCursor()) }

        fun wordBeforeCursor(): String {
            val t = ime(ctx)?.currentInputConnection?.getTextBeforeCursor(40, 0)?.toString() ?: return ""
            return t.trimEnd().split(Regex("\\s+")).lastOrNull()?.trim { !it.isLetterOrDigit() } ?: ""
        }

        fun search(q: String) {
            val needle = q.trim().lowercase()
            val items = if (needle.isEmpty()) STICKERS else STICKERS.filter { sticker ->
                sticker.title.lowercase().contains(needle) ||
                    sticker.tags.split(Regex("\\s+")).any { it.startsWith(needle) }
            }
            grid.removeAllViews()
            val my = ++gen
            status.text = "Встроенные GIF · CC0 1.0 · без ключа и интернета"
            io.execute {
                val dir = try { stickerDir(ctx) } catch (_: Exception) { null }
                val previews = items.map { sticker ->
                    sticker to dir?.let { BitmapFactory.decodeFile(File(it, "${sticker.id}.gif").absolutePath) }
                }
                main.post {
                    if (my != gen) return@post
                    if (items.isEmpty()) { status.text = "В локальном наборе ничего не найдено"; return@post }
                    val w = (frame.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels) / 3
                    previews.forEach { (sticker, bmp) ->
                        val iv = ImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setBackgroundColor(0xFF333333.toInt())
                            contentDescription = sticker.title
                            if (bmp != null) setImageBitmap(bmp)
                            setOnClickListener { insertLocal(sticker.id) }
                        }
                        grid.addView(iv, GridLayout.LayoutParams().apply {
                            width = w - ctx.dp(4); height = (w * 0.75).toInt()
                            setMargins(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
                        })
                    }
                }
            }
        }

        fun insertLocal(id: String) {
            status.text = "Подготавливаю GIF…"
            io.execute {
                val file = try {
                    val source = File(stickerDir(ctx), "$id.gif")
                    if (!source.isFile) throw IllegalStateException("GIF asset missing: $id")
                    val dir = File(ctx.filesDir, "gif").apply {
                        mkdirs()
                        listFiles()?.sortedBy { it.lastModified() }?.dropLast(20)?.forEach { it.delete() }
                    }
                    File(dir, "klaviatura_$id.gif").also { target ->
                        if (!target.exists()) source.copyTo(target, overwrite = true)
                    }
                } catch (e: Exception) { null }
                main.post {
                    if (file == null) { status.text = "Не удалось подготовить GIF"; return@post }
                    status.text = ""
                    try {
                        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.getString(R.string.clipboard_provider_authority), file)
                        listener.onContent(InputContentInfoCompat(uri, ClipDescription("GIF", arrayOf("image/gif")), null))
                    } catch (e: Exception) {
                        status.text = "Поле не принимает GIF. Попробуйте поле с поддержкой изображений."
                    }
                }
            }
        }
    }
}
