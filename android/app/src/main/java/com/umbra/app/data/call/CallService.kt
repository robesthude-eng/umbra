package com.umbra.app.data.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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
        // ServiceCompat сам игнорирует тип на Android 9 и ниже.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(title, video), foregroundType(video))
        } catch (e: Exception) {
            // Android 14+ отказывает в старте службы из фона или без разрешения.
            // Службу тут же останавливаем: иначе система убьёт всё приложение
            // за то, что она так и не вышла на передний план, а разговор
            // продолжается пока экран звонка открыт.
            Log.w(TAG, "startForeground failed", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * Типы службы собираем по выданным разрешениям: на Android 14+ тип
     * microphone без RECORD_AUDIO бросает SecurityException и роняет приложение.
     */
    private fun foregroundType(video: Boolean): Int {
        var type = 0
        if (granted(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (video && granted(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        return type
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

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
        private const val TAG = "CallService"
    }
}
