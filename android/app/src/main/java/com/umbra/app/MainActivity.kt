package com.umbra.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.umbra.app.data.call.CallNotifications
import com.umbra.app.di.AppContainer
import com.umbra.app.data.session.ThemeMode
import com.umbra.app.ui.UmbraRoot
import com.umbra.app.ui.FutureBackdrop
import com.umbra.app.ui.AlienActivationOverlay
import com.umbra.app.ui.theme.UmbraTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val pictureInPicture = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pictureInPicture.value = isInPictureInPictureMode
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val container = (application as UmbraApp).container
        // Входящий звонок должен подниматься поверх блокировки и будить экран.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        handleCallIntent(intent, container)
        setContent {
            val appearance by container.uiPreferences.state.collectAsState()
            val inPictureInPicture by pictureInPicture.collectAsState()
            val activeCall by container.chatRepository.activeCall.collectAsState()
            LaunchedEffect(inPictureInPicture, activeCall?.callId) {
                if (inPictureInPicture && activeCall == null) finish()
            }
            val systemDark = isSystemInDarkTheme()
            val dark = when (appearance.theme) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            UmbraTheme(
                darkTheme = dark,
                dynamicColor = appearance.dynamicColor,
                messageTextSize = appearance.messageTextSize,
                reduceMotion = appearance.reduceMotion,
                alienIntensity = appearance.alienIntensity,
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        FutureBackdrop(Modifier.matchParentSize())
                        UmbraRoot(container, inPictureInPicture)
                        // Короткая заставка при включении Alien-режима; касания не перехватывает.
                        AlienActivationOverlay(Modifier.matchParentSize())
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallIntent(intent, (application as UmbraApp).container)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val container = (application as UmbraApp).container
        val call = container.chatRepository.activeCall.value
        val media = container.callEngine.media.value
        if (call?.video == true && !call.ringing && media?.connected == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build()
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPicture.value = isInPictureInPictureMode
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
