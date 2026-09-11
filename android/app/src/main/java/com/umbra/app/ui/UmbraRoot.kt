package com.umbra.app.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.umbra.app.data.repo.SessionPhase
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.delay

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
            ScreenEntrance(chatId ?: "main", Modifier.fillMaxSize()) {
                if (chatId != null) screens.SaveableStateProvider("chat:$chatId") {
                    ChatView(container, chatId) { openChatId = null }
                } else screens.SaveableStateProvider("main") {
                    MainShell(container, tab, { tab = it }, { openChatId = it })
                }
            }
            val call by repo.activeCall.collectAsState()
            call?.let { CallScreen(container, it) }
        }
    }
}
