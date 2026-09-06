package com.umbra.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.umbra.app.di.AppContainer

/** Корневой экран: навигация auth → chats → chat/{id}. */
@Composable
fun UmbraRoot(container: AppContainer) {
    val repo = container.chatRepository
    val nav = rememberNavController()
    var loggedIn by remember { mutableStateOf(repo.isLoggedIn()) }

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
            AuthScreen(container, onAuthed = { loggedIn = true })
        }
        composable("chats") {
            ChatsScreen(
                container = container,
                onOpenChat = { chatId -> nav.navigate("chat/$chatId") },
                onLogout = { loggedIn = false },
            )
        }
        composable("chat/{chatId}") { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            ChatScreen(container = container, chatId = chatId, onBack = { nav.popBackStack() })
        }
    }
}
