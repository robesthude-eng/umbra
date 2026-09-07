package com.umbra.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.umbra.app.data.media.MediaInfo
import com.umbra.app.data.repo.UiMessage
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class ChatViewModel(
    private val container: AppContainer,
    private val chatId: String,
) : ViewModel() {
    private val repo = container.chatRepository

    val messages: StateFlow<List<UiMessage>> = repo.messagesFor(chatId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Идёт отправка медиа (чтение + шифрование + загрузка + отправка). */
    private val _sendingMedia = MutableStateFlow(false)
    val sendingMedia: StateFlow<Boolean> = _sendingMedia.asStateFlow()

    /** Последняя ошибка отправки/скачивания медиа; null — всё хорошо. */
    private val _mediaError = MutableStateFlow<String?>(null)
    val mediaError: StateFlow<String?> = _mediaError.asStateFlow()

    fun send(text: String, expiresIn: Long? = null) {
        if (text.isBlank()) return
        viewModelScope.launch {
            runCatching { repo.sendMessage(chatId, text.trim(), expiresIn) }
        }
    }

    /** Отправляет файл/фото из системного пикера как E2E-медиа. */
    fun sendMedia(uri: Uri, caption: String = "") {
        viewModelScope.launch {
            _sendingMedia.value = true
            _mediaError.value = null
            runCatching { repo.sendMedia(chatId, uri, caption.trim()) }
                .onFailure { _mediaError.value = it.message ?: "не удалось отправить медиа" }
            _sendingMedia.value = false
        }
    }

    /** Скачивает и расшифровывает медиа в приватный кэш; файл пригоден для показа/сохранения. */
    suspend fun fetchMedia(info: MediaInfo): File = repo.fetchMediaFile(info)

    fun dismissMediaError() {
        _mediaError.value = null
    }

    class Factory(private val container: AppContainer, private val chatId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(container, chatId) as T
    }
}
