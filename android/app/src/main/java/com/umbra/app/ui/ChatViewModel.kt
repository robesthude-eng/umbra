package com.umbra.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.umbra.app.data.repo.UiMessage
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val container: AppContainer,
    private val chatId: String,
) : ViewModel() {
    private val repo = container.chatRepository

    val messages: StateFlow<List<UiMessage>> = repo.messagesFor(chatId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun send(text: String, expiresIn: Long? = null) {
        if (text.isBlank()) return
        viewModelScope.launch {
            runCatching { repo.sendMessage(chatId, text.trim(), expiresIn) }
        }
    }

    class Factory(private val container: AppContainer, private val chatId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(container, chatId) as T
    }
}
