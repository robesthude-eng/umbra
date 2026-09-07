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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

class ChatViewModel(
    private val container: AppContainer,
    private val chatId: String,
) : ViewModel() {
    private val repo = container.chatRepository

    val messages: StateFlow<List<UiMessage>> = repo.messagesFor(chatId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sending = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    fun send(text: String, onQueued: () -> Unit) {
        if (text.isBlank() || sending.value) return
        sending.value = true
        viewModelScope.launch {
            try {
                error.value = null
                repo.sendMessage(chatId, text.trim())
                onQueued()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.userMessage() }
            finally { sending.value = false }
        }
    }

    fun retry(id: String) {
        viewModelScope.launch {
            try { repo.retry(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.userMessage() }
        }
    }

    class Factory(private val container: AppContainer, private val chatId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(container, chatId) as T
    }
}
