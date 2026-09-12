package com.umbra.app.data.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.umbra.app.MainActivity
import com.umbra.app.R
import com.umbra.app.UmbraApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Уведомление о входящем звонке — то самое «как в Telegram»: экран поднимается
 * поверх блокировки, звонит рингтон, есть кнопки «Ответить» и «Отклонить».
 *
 * Показывается по push-сообщению, когда приложение закрыто и WebSocket не
 * работает. Полноэкранное намерение (setFullScreenIntent) требует высокой
 * важности канала и разрешения USE_FULL_SCREEN_INTENT; если система его не
 * даёт, Android сам покажет обычное уведомление сверху — звонок не потеряется.
 */
object CallNotifications {
    const val EXTRA_CALL_ID = "umbra.call_id"
    const val EXTRA_PEER_ID = "umbra.peer_id"
    const val EXTRA_PEER_NAME = "umbra.peer_name"
    const val EXTRA_VIDEO = "umbra.video"
    const val EXTRA_ANSWER = "umbra.answer"
    const val ACTION_DECLINE = "com.umbra.app.action.CALL_DECLINE"

    private const val ACTION_SHOW = "com.umbra.app.action.CALL_SHOW"
    private const val CHANNEL_ID = "umbra_incoming_calls"
    private const val NOTIFICATION_ID = 4202

    /** Столько же, сколько ждёт вызывающий: дальше звонок считается пропущенным. */
    private const val RING_TIMEOUT_MS = 45_000L

    @Volatile
    private var shownCallId: String? = null

    fun show(context: Context, callId: String, peerId: String, peerName: String, video: Boolean) {
        if (callId.isBlank()) return
        ensureChannel(context)
        val name = peerName.ifBlank { "Собеседник" }
        val open = activityIntent(context, callId, peerId, name, video, answer = false)
        val answer = activityIntent(context, callId, peerId, name, video, answer = true)
        val decline = PendingIntent.getBroadcast(
            context,
            requestCode(callId, "decline"),
            Intent(context, CallActionReceiver::class.java)
                .setAction(ACTION_DECLINE)
                .putExtra(EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (video) "Входящий видеозвонок" else "Входящий звонок")
            .setContentText(name)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, "Ответить", answer)
            .addAction(0, "Отклонить", decline)
            .build()
        shownCallId = callId
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Android 13+: разрешение на уведомления не выдано. Молча выходим —
            // при открытом приложении звонок всё равно придёт по WebSocket.
            shownCallId = null
        }
    }

    /**
     * Гасит уведомление. Пустой [callId] снимает любое (например, при выходе из
     * аккаунта); непустой — только если показан именно этот звонок, чтобы
     * запоздавшее «звонок завершён» не убрало следующий вызов.
     */
    fun cancel(context: Context, callId: String = "") {
        val shown = shownCallId
        if (callId.isNotBlank() && shown != null && shown != callId) return
        shownCallId = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun activityIntent(
        context: Context,
        callId: String,
        peerId: String,
        peerName: String,
        video: Boolean,
        answer: Boolean,
    ): PendingIntent {
        // Уникальное действие: иначе PendingIntent считается тем же и подменяет
        // extras у уже созданного намерения.
        val suffix = if (answer) "answer" else "open"
        val intent = Intent(context, MainActivity::class.java)
            .setAction("$ACTION_SHOW.$callId.$suffix")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_CALL_ID, callId)
            .putExtra(EXTRA_PEER_ID, peerId)
            .putExtra(EXTRA_PEER_NAME, peerName)
            .putExtra(EXTRA_VIDEO, video)
            .putExtra(EXTRA_ANSWER, answer)
        return PendingIntent.getActivity(
            context,
            requestCode(callId, suffix),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requestCode(callId: String, suffix: String): Int = (callId + suffix).hashCode()

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(CHANNEL_ID, "Входящие звонки", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Звонок поверх экрана блокировки"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            setShowBadge(false)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), attributes)
        }
        manager.createNotificationChannel(channel)
    }
}

/**
 * Кнопка «Отклонить» в уведомлении. Приложение при этом может быть закрыто,
 * поэтому репозиторий берём из [UmbraApp], а ответ сервера ждём через
 * goAsync(): без него процесс могут убить до отправки запроса.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CallNotifications.ACTION_DECLINE) return
        val callId = intent.getStringExtra(CallNotifications.EXTRA_CALL_ID).orEmpty()
        CallNotifications.cancel(context, callId)
        val app = context.applicationContext as? UmbraApp ?: return
        if (callId.isBlank()) return
        val repo = app.container.chatRepository
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repo.declineCall(callId)
            } catch (_: Exception) {
                // Сервер сам переведёт звонок в missed по таймауту.
            } finally {
                pending.finish()
            }
        }
    }
}
