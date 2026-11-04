package ru.klaviatura.voice

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/** asks for microphone / notification permission; also handles "retry download" from the notification */
class VoicePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceInput.init(this)
        intent.getStringExtra("retry")?.let { VoiceInput.download(it); finish(); return }
        if (intent.getBooleanExtra("notif", false)) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) else finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 1)
            Toast.makeText(this, if (grantResults.firstOrNull() == 0) "Микрофон разрешён — нажмите микрофон ещё раз" else "Без микрофона голосовой ввод не работает", Toast.LENGTH_LONG).show()
        finish()
    }
}
