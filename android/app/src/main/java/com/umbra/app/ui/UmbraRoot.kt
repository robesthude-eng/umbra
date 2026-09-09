package com.umbra.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.umbra.app.data.repo.ActiveCall
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.data.repo.SessionPhase
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun UmbraRoot(container: AppContainer) {
    val repo = container.chatRepository
    val phase by repo.phase.collectAsState()
    LaunchedEffect(phase) {
        if (phase == SessionPhase.READY) {
            repo.startRealtime()
            while (true) {
                repo.deleteExpiredMessages()
                delay(1000)
            }
        } else repo.stopRealtime()
    }
    when (phase) {
        SessionPhase.LOGGED_OUT, SessionPhase.NEEDS_PROFILE -> AuthScreen(container, onDone = {})
        SessionPhase.READY -> key(repo.me()) {
            var openChatId by rememberSaveable { mutableStateOf<String?>(null) }
            var tab by rememberSaveable { mutableIntStateOf(0) }
            val screens = rememberSaveableStateHolder()
            val chatId = openChatId
            if (chatId != null) screens.SaveableStateProvider("chat:$chatId") {
                ChatView(container, chatId) { openChatId = null }
            } else screens.SaveableStateProvider("main") {
                MainShell(container, tab, { tab = it }, { openChatId = it })
            }
            val call by repo.activeCall.collectAsState()
            call?.let { CallOverlay(it, repo) }
        }
    }
}

@Composable
private fun CallOverlay(call: ActiveCall, repo: ChatRepository) {
    var busy by remember(call.callId) { mutableStateOf(false) }
    var error by remember(call.callId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val users by repo.userCache.collectAsState()
    val name = users[call.peerUserId]?.fullName() ?: call.peerName
    fun act(status: String) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { repo.setCallStatus(status) }
            catch (e: Exception) { error = e.userMessage() }
            finally { busy = false }
        }
    }
    LaunchedEffect(call.callId) {
        while (true) {
            try { repo.fetchCalls() }
            catch (e: Exception) { error = e.userMessage() }
            delay(5000)
        }
    }
    LaunchedEffect(call.callId, call.ringing) {
        if (call.ringing) {
            delay(60_000)
            act("missed")
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) act(if (call.incoming && call.ringing) "declined" else "ended") },
        title = { Text(if (call.ringing) if (call.incoming) "Входящий тестовый вызов" else "Ожидание ответа…" else "Тестовый вызов принят") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge)
                Text("Звук и видео в этой версии недоступны.")
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton({ repo.dismissCallLocally() }, enabled = !busy) { Text("Закрыть на этом устройстве") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            if (call.incoming && call.ringing) TextButton({ act("active") }, enabled = !busy) { Text("Принять тест") }
            else TextButton({ act("ended") }, enabled = !busy) { Text("Завершить") }
        },
        dismissButton = {
            if (call.incoming && call.ringing) TextButton({ act("declined") }, enabled = !busy) { Text("Отклонить") }
        },
    )
}
