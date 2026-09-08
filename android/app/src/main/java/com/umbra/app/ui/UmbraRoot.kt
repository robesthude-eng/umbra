package com.umbra.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.launch

/** Корневой экран: навигация auth → chats → chat/{id}. */
@Composable
fun UmbraRoot(container: AppContainer) {
    val repo = container.chatRepository
    val nav = rememberNavController()
    val loggedIn by repo.loggedIn.collectAsState()
    val scope = rememberCoroutineScope()

    // Подключаем realtime (WebSocket) только в авторизованном состоянии.
    LaunchedEffect(loggedIn) {
        if (loggedIn) repo.connectRealtime() else repo.disconnectRealtime()
    }

    // Реактивная переадресация по состоянию авторизации.
    LaunchedEffect(loggedIn) {
        if (loggedIn) {
            nav.navigate("chats") { popUpTo("auth") { inclusive = true } }
        } else {
            nav.navigate("auth") { popUpTo(0) { inclusive = true } }
        }
    }

    NavHost(navController = nav, startDestination = "auth") {
        composable("auth") {
            AuthScreen(container, onAuthed = { })
        }
        composable("chats") {
            ChatsScreen(
                container = container,
                onOpenChat = { chatId -> nav.navigate("chat/$chatId") },
                onOpenContacts = { nav.navigate("contacts") },
                onLogout = { scope.launch { repo.logout() } },
            )
        }
        composable("contacts") {
            ContactsScreen(
                container = container,
                onOpenChat = { chatId -> nav.navigate("chat/$chatId") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("chat/{chatId}") { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            ChatScreen(container = container, chatId = chatId, onBack = { nav.popBackStack() })
        }
    }
}
