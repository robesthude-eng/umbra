package com.umbra.app.data.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.umbra.app.MainActivity
import com.umbra.app.R

/**
 * Служба переднего плана на время звонка.
 *
 * Начиная с Android 14 доступ к микрофону и камере в фоне разрешён только
 * службе с типами microphone/camera. Без неё разговор бы глох при свёртывании
 * приложения. Уведомление ведёт обратно на экран звонка.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Собеседник"
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) ?: false
        ensureChannel()
        val type = if (video) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        // ServiceCompat сам игнорирует тип на Android 9 и ниже.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(title, video), type)
        return START_NOT_STICKY
    }

    private fun notification(title: String, video: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (video) "Видеозвонок" else "Звонок")
            .setContentText(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setShowWhen(false)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Звонки", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Идёт разговор"
            setShowBadge(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_VIDEO = "video"
        private const val CHANNEL_ID = "umbra_calls"
        private const val NOTIFICATION_ID = 4201
    }
}
