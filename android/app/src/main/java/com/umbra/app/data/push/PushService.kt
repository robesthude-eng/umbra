package com.umbra.app.data.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import com.umbra.app.MainActivity
import com.umbra.app.R
import com.umbra.app.UmbraApp
import com.umbra.app.data.call.CallNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Приём push-уведомлений Firebase.
 *
 * Сервер присылает только data-сообщения (без блока notification), чтобы
 * уведомление рисовало приложение: только так входящий звонок можно показать
 * поверх блокировки и погасить, когда звонящий бросил трубку.
 *
 * Содержимое переписки в push не передаётся — только «от кого» и идентификаторы,
 * чтобы текст не проходил через серверы Google.
 */
class PushService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val repo = (applicationContext as? UmbraApp)?.container?.chatRepository ?: return
        scope.launch {
            try {
                repo.registerPushToken(token)
            } catch (_: Exception) {
                // Не вошли в аккаунт или нет сети — токен отправим при следующем запуске.
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val repo = (applicationContext as? UmbraApp)?.container?.chatRepository
        when (data["kind"]) {
            "call" -> {
                val callId = data["call_id"].orEmpty()
                if (callId.isBlank()) return
                val peerId = data["caller_id"].orEmpty()
                val peerName = data["caller_name"].orEmpty()
                val video = data["video"] == "true"
                // Сначала уведомление (работает даже без входа в аккаунт), потом состояние.
                CallNotifications.show(this, callId, peerId, peerName, video)
                repo?.showIncomingCallFromPush(callId, peerId, peerName, video)
            }
            "call_ended" -> {
                val callId = data["call_id"].orEmpty()
                CallNotifications.cancel(this, callId)
                repo?.dismissCallFromPush(callId)
            }
            "message" -> {
                MessageNotifications.show(
                    this,
                    senderName = data["sender_name"].orEmpty(),
                    threadKey = data["chat_id"].orEmpty().ifBlank { data["sender_id"].orEmpty() },
                )
                // Подтягиваем само сообщение: к моменту открытия оно уже в базе.
                scope.launch {
                    try {
                        repo?.refresh()
                    } catch (_: Exception) {
                        // Обычный случай: нет сети. Синхронизация пройдёт при открытии.
                    }
                }
            }
            else -> Log.d(TAG, "push: unknown kind")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "UmbraPush"

        /**
         * Запрашивает токен у Firebase и отдаёт его серверу.
         *
         * Если google-services.json в сборку не положили, FirebaseApp не
         * инициализирован — тихо выходим: приложение работает и без push,
         * просто не будит телефон при закрытом приложении.
         */
        fun syncToken(context: Context) {
            val app = context.applicationContext as? UmbraApp ?: return
            if (FirebaseApp.getApps(app).isEmpty()) return
            val repo = app.container.chatRepository
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val token = task.result
                if (!task.isSuccessful || token.isNullOrBlank()) {
                    Log.w(TAG, "push: no token (${task.exception?.message})")
                    return@addOnCompleteListener
                }
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        repo.registerPushToken(token)
                    } catch (_: Exception) {
                        // Токен повторим при следующем запуске приложения.
                    }
                }
            }
        }

        /** При выходе из аккаунта убиваем токен, чтобы чужие уведомления не пришли. */
        fun dropToken(context: Context) {
            val app = context.applicationContext as? UmbraApp ?: return
            if (FirebaseApp.getApps(app).isEmpty()) return
            try {
                FirebaseMessaging.getInstance().deleteToken()
            } catch (_: Exception) {
                // Сервер уже отвязал токен от аккаунта — этого достаточно.
            }
        }
    }
}

/**
 * Уведомление о новом сообщении. Текст не показываем: сервер его и не присылает.
 */
private const val REPLY_KEY = "umbra_reply_text"
private const val EXTRA_THREAD = "umbra_reply_thread"

class MessageReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY_KEY)?.toString()?.trim().orEmpty()
        val thread = intent.getStringExtra(EXTRA_THREAD).orEmpty()
        if (text.isBlank() || thread.isBlank()) return
        val pending = goAsync()
        val app = context.applicationContext as? UmbraApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = app?.container?.chatRepository ?: return@launch
                repo.sendText(thread, text)
                NotificationManagerCompat.from(context).cancel(thread.hashCode())
            } catch (e: Exception) {
                Log.w("UmbraPush", "quick reply was not queued", e)
            } finally { pending.finish() }
        }
    }
}

private object MessageNotifications {
    private const val CHANNEL_ID = "umbra_messages"

    fun show(context: Context, senderName: String, threadKey: String) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            threadKey.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val who = senderName.ifBlank { "Новое сообщение" }
        val replyIntent = Intent(context, MessageReplyReceiver::class.java).putExtra(EXTRA_THREAD, threadKey)
        val replyPending = PendingIntent.getBroadcast(
            context, threadKey.hashCode(), replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(REPLY_KEY).setLabel("Ответить").build()
        val replyAction = NotificationCompat.Action.Builder(
            R.mipmap.ic_launcher, "Ответить", replyPending,
        ).addRemoteInput(remoteInput).build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(who)
            .setContentText("Новое сообщение в Umbra")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(replyAction)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(threadKey.hashCode(), notification)
        } catch (_: SecurityException) {
            // Разрешение POST_NOTIFICATIONS не выдано.
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Сообщения", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Новые сообщения, когда приложение закрыто"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }
}
