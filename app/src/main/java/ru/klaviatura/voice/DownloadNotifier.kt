package ru.klaviatura.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/** notification with progress bar (% and MB), unzip stage, error with retry */
class DownloadNotifier(private val c: Context, private val lang: String) {
    private val nm = c.getSystemService(NotificationManager::class.java)
    private val id = 7100 + lang.hashCode() % 100
    private val name = if (lang == "ru") "русская" else "английская"
    init {
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("kl_voice_dl", "Загрузка голосовых моделей", NotificationManager.IMPORTANCE_LOW))
    }
    private fun b() = NotificationCompat.Builder(c, "kl_voice_dl").setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Голосовая модель: $name").setOnlyAlertOnce(true)
    private fun post(n: android.app.Notification) = try { nm.notify(id, n) } catch (_: SecurityException) {}

    fun progress(pct: Int, done: Long, total: Long) = post(b().setOngoing(true)
        .setProgress(100, pct, total <= 0)
        .setContentText(if (total > 0) "Скачано $pct% — ${VoiceInput.mb(done)} из ${VoiceInput.mb(total)} МБ" else "Скачано ${VoiceInput.mb(done)} МБ")
        .build())
    fun unzipping() = post(b().setOngoing(true).setProgress(0, 0, true).setContentText("Распаковка модели…").build())
    fun done() = post(b().setSmallIcon(android.R.drawable.stat_sys_download_done).setAutoCancel(true)
        .setContentText("Готово. Нажмите микрофон на клавиатуре").build())
    fun error(msg: String) {
        val retry = PendingIntent.getActivity(c, id, Intent(c, VoicePermissionActivity::class.java).putExtra("retry", lang)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        post(b().setSmallIcon(android.R.drawable.stat_notify_error).setAutoCancel(true)
            .setContentText("Ошибка: $msg").setStyle(NotificationCompat.BigTextStyle().bigText("Не удалось скачать модель: $msg"))
            .setContentIntent(retry).addAction(0, "Повторить", retry).build())
    }
}
