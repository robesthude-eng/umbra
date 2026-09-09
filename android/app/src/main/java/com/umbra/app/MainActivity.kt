package com.umbra.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.umbra.app.data.call.CallNotifications
import com.umbra.app.di.AppContainer
import com.umbra.app.ui.UmbraRoot
import com.umbra.app.ui.theme.UmbraTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as UmbraApp).container
        // Входящий звонок должен подниматься поверх блокировки и будить экран.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        handleCallIntent(intent, container)
        setContent {
            UmbraTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    UmbraRoot(container)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallIntent(intent, (application as UmbraApp).container)
    }

    /**
     * Приложение открыли из уведомления о звонке: восстанавливаем вызов,
     * которого нет в памяти (приложение было закрыто), и гасим уведомление.
     */
    private fun handleCallIntent(intent: Intent?, container: AppContainer) {
        val callId = intent?.getStringExtra(CallNotifications.EXTRA_CALL_ID)?.takeIf { it.isNotBlank() } ?: return
        val repo = container.chatRepository
        repo.showIncomingCallFromPush(
            callId,
            intent.getStringExtra(CallNotifications.EXTRA_PEER_ID).orEmpty(),
            intent.getStringExtra(CallNotifications.EXTRA_PEER_NAME).orEmpty(),
            intent.getBooleanExtra(CallNotifications.EXTRA_VIDEO, false),
        )
        CallNotifications.cancel(this, callId)
        val answer = intent.getBooleanExtra(CallNotifications.EXTRA_ANSWER, false)
        // Повторный onNewIntent с тем же намерением не должен снова «отвечать».
        intent.removeExtra(CallNotifications.EXTRA_ANSWER)
        if (!answer) return
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        // Без микрофона отвечать нечем: экран звонка сам попросит разрешение.
        if (!micGranted) return
        lifecycleScope.launch {
            runCatching { repo.setCallStatus("active") }
        }
    }
}
