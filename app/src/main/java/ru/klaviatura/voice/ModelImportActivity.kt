// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kotlin.concurrent.thread

/** «Импорт модели из файла»: user picks a Vosk model .zip (e.g. downloaded in a browser) */
class ModelImportActivity : Activity() {
    private var lang = "ru"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceInput.init(this)
        lang = intent.getStringExtra("lang") ?: VoiceInput.currentLang().let { if (VoiceInput.supported(it)) it else "ru" }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 1)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { finish(); return }
        val app = applicationContext
        val name = uri.lastPathSegment.orEmpty().lowercase()
        val l = when { "-ru" in name -> "ru"; "-en" in name -> "en"; else -> lang }
        Toast.makeText(app, "Устанавливаю модель ($l)…", Toast.LENGTH_SHORT).show()
        thread {
            val msg = try {
                VoiceInput.modelDir(l).parentFile?.mkdirs()
                app.contentResolver.openInputStream(uri)!!.use { ModelDownloader.install(it, VoiceInput.modelDir(l)) }
                "Модель «$l» установлена — нажмите микрофон"
            } catch (e: Exception) { "Не удалось импортировать: ${ModelDownloader.humanError(e)}" }
            runOnUiThread { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
        }
        finish()
    }
}
