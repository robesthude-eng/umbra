package com.umbra.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.umbra.app.data.db.ContactEntity
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ContactsViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.chatRepository

    val contacts: StateFlow<List<ContactEntity>> = repo.contacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** Результат «написать контакту»: id открытого диалога. */
    private val _startedChatId = MutableStateFlow<String?>(null)
    val startedChatId: StateFlow<String?> = _startedChatId

    /** Синхронизирует телефонную книгу с сервером (только хэши номеров). */
    fun sync() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            _error.value = null
            try {
                repo.syncContacts()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _error.value = e.userMessage() }
            finally { _syncing.value = false }
        }
    }

    /** Открывает диалог с контактом, зарегистрированным в Umbra. */
    fun startChat(contact: ContactEntity) {
        val username = contact.umbraUsername ?: return
        if (_syncing.value) return
        viewModelScope.launch {
            _error.value = null
            try {
                val chat = repo.startChat(username, title = contact.name)
                _startedChatId.value = chat.id
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _error.value = e.userMessage() }
        }
    }

    fun consumeStartedChat() {
        _startedChatId.value = null
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ContactsViewModel(container) as T
    }
}
