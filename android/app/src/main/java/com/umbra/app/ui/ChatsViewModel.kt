package com.umbra.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.umbra.app.data.db.ChatEntity
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatsViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.chatRepository

    val chats: StateFlow<List<ChatEntity>> = repo.chats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { runCatching { repo.refreshChats() } }
    }

    fun logout() {
        repo.logout()
        // Перезапуск через UmbraRoot: isLoggedIn() станет false.
    }

    fun startNewChat() {
        // MVP: диалог ввода recipient; здесь заглушка.
    }

    fun openChat(chat: ChatEntity) {
        // Переход к ChatScreen (навигация добавлена в UmbraRoot по мере необходимости).
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatsViewModel(container) as T
    }
}
