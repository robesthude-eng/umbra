package com.umbra.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.umbra.app.data.db.ChatEntity
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class ChatsViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.chatRepository

    val chats: StateFlow<List<ChatEntity>> = repo.chats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Текущее состояние диалога «новый чат». */
    val newChatState = MutableStateFlow<NewChatState>(NewChatState.Hidden)
    sealed interface NewChatState {
        data object Hidden : NewChatState
        data object Dialog : NewChatState
        data object Loading : NewChatState
        data class Error(val message: String) : NewChatState
        data class Started(val chat: ChatEntity) : NewChatState
    }

    /** Полное удаление аккаунта: сервер стирает все данные, локально — ключи и история. */
    fun deleteAccount() {
        viewModelScope.launch {
            try { repo.deleteAccount() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        }
    }

    fun showNewChatDialog() = newChatState.tryEmit(NewChatState.Dialog)
    fun hideNewChatDialog() = newChatState.tryEmit(NewChatState.Hidden)

    /** Создаёт диалог по имени пользователя и уведомляет об успехе через [newChatState]. */
    fun startChat(username: String) {
        if (username.isBlank() || newChatState.value == NewChatState.Loading) return
        viewModelScope.launch {
            newChatState.value = NewChatState.Loading
            try { newChatState.value = NewChatState.Started(repo.startChat(username.trim())) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { newChatState.value = NewChatState.Error(e.userMessage()) }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatsViewModel(container) as T
    }
}
