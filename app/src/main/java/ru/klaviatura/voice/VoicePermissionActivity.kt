package ru.klaviatura.voice

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class VoicePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        Toast.makeText(this, if (grantResults.firstOrNull() == 0) "Микрофон разрешён — нажмите микрофон ещё раз" else "Без микрофона голосовой ввод не работает", Toast.LENGTH_LONG).show()
        finish()
    }
}
