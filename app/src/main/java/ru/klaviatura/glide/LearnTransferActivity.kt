package ru.klaviatura.glide

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** export / import of the learned glide dictionary through the system file picker */
class LearnTransferActivity : Activity() {
    private var export = true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GlideEngine.init(this)
        export = intent.getBooleanExtra("export", true)
        val i = if (export) Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/tab-separated-values")
            .putExtra(Intent.EXTRA_TITLE, "klaviatura_slova.tsv")
        else Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
        startActivityForResult(i.addCategory(Intent.CATEGORY_OPENABLE), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) try {
            if (export) {
                contentResolver.openOutputStream(uri)?.use { it.write(GlideEngine.export().toByteArray()) }
                Toast.makeText(this, "Сохранено слов: ${GlideEngine.learnedWordCount}", Toast.LENGTH_LONG).show()
            } else {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
                if (!text.startsWith("#klaviatura-learn")) throw IllegalArgumentException("это не файл словаря Клавиатуры")
                GlideEngine.import(text, false)
                Toast.makeText(this, "Импортировано. Слов: ${GlideEngine.learnedWordCount}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) { Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show() }
        finish()
    }
}
