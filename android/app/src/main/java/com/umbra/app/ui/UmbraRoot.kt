package com.umbra.app.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.umbra.app.data.repo.SessionPhase
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.delay

@Composable
fun UmbraRoot(container: AppContainer, pictureInPicture: Boolean = false) {
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
            var minimizedCallId by rememberSaveable { mutableStateOf<String?>(null) }
            val screens = rememberSaveableStateHolder()
            val call by repo.activeCall.collectAsState()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val twoPane = maxWidth >= 840.dp
                val chatId = openChatId
                Box(Modifier.fillMaxSize()) {
                    if (twoPane) screens.SaveableStateProvider("main") {
                        MainShell(
                            container = container,
                            tab = tab,
                            onTab = { tab = it },
                            onOpenChat = { openChatId = it },
                            selectedChatId = chatId,
                            twoPane = true,
                            onCloseChat = { openChatId = null },
                            sharedChatStateHolder = screens,
                        )
                    } else ScreenEntrance(chatId ?: "main", Modifier.fillMaxSize()) {
                        if (chatId != null) screens.SaveableStateProvider("chat:$chatId") {
                            ChatView(container, chatId) { openChatId = null }
                        } else screens.SaveableStateProvider("main") {
                            MainShell(container, tab, { tab = it }, { openChatId = it })
                        }
                    }
                    call?.let { active ->
                        if (minimizedCallId == active.callId && !active.ringing && !pictureInPicture) {
                            Box(Modifier.align(Alignment.TopCenter)) {
                                MinimizedCallBar(container, active) { minimizedCallId = null }
                            }
                        } else {
                            CallScreen(container, active, pictureInPicture) { minimizedCallId = active.callId }
                        }
                    }
                }
            }
        }
    }
}
