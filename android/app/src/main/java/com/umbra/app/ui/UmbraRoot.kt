package com.umbra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.umbra.app.data.repo.ActiveCall
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.data.repo.SessionPhase
import com.umbra.app.di.AppContainer
import com.umbra.app.ui.theme.UmbraColors
import kotlinx.coroutines.launch

/**
 * Корень приложения (v0.4, T1): в зависимости от фазы сессии показывает вход
 * или основную навигацию (Чаты/Группы/Звонки/Настройки), поверх — оверлей
 * активного звонка.
 */
@Composable
fun UmbraRoot(container: AppContainer) {
    val repo = container.chatRepository
    val phase by repo.phase.collectAsState()
    val activeCall by repo.activeCall.collectAsState()
    var openChatId by rememberSaveable { mutableStateOf<String?>(null) }

    // Вход выполнен — поднимаем realtime (WS + инкрементальная синхронизация).
    // Вышли/удалили аккаунт — останавливаем.
    LaunchedEffect(phase) {
        when (phase) {
            SessionPhase.READY -> repo.startRealtime()
            else -> repo.stopRealtime()
        }
    }

    when (phase) {
        SessionPhase.LOGGED_OUT, SessionPhase.NEEDS_PROFILE -> {
            AuthScreen(container, onDone = {})
        }
        SessionPhase.READY -> {
            val chatId = openChatId
            if (chatId != null) {
                ChatView(container, chatId, onBack = { openChatId = null })
            } else {
                MainShell(container, onOpenChat = { openChatId = it })
            }
        }
    }

    activeCall?.let { call -> CallOverlay(call, repo) }
}

/** Оверлей звонка: сигналинг T1 (медиа-поток добавится в следующей версии). */
@Composable
private fun CallOverlay(call: ActiveCall, repo: ChatRepository) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun act(status: String) {
        if (busy) return
        busy = true
        scope.launch { repo.setCallStatus(status) }
    }

    Dialog(onDismissRequest = { /* только кнопками */ }) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = UmbraColors.GlassDark,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.size(72.dp).background(UmbraColors.headerGradient, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Call, null, tint = Color.White) }
                Spacer(Modifier.height(16.dp))
                Text(
                    when {
                        call.incoming && call.ringing -> "Входящий звонок"
                        call.ringing -> "Вызов…"
                        else -> "На связи"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = UmbraColors.Ice,
                )
                Text(
                    call.peerName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = UmbraColors.Aqua,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (call.incoming && call.ringing) "Примите вызов" else "Медиа-поток появится в следующей версии",
                    style = MaterialTheme.typography.bodySmall,
                    color = UmbraColors.Fog,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (call.incoming && call.ringing) {
                        FilledIconButton(
                            onClick = { act("active") },
                            enabled = !busy,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = UmbraColors.Mint),
                        ) { Icon(Icons.Filled.Call, "Ответить", tint = Color(0xFF00382E)) }
                        FilledIconButton(
                            onClick = { act("declined") },
                            enabled = !busy,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = UmbraColors.Danger),
                        ) { Icon(Icons.Filled.CallEnd, "Отклонить", tint = Color.White) }
                    } else {
                        FilledIconButton(
                            onClick = { act("ended") },
                            enabled = !busy,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = UmbraColors.Danger),
                        ) { Icon(Icons.Filled.CallEnd, "Завершить", tint = Color.White) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
