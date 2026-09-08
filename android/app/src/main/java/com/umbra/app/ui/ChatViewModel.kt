package com.umbra.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.umbra.app.data.db.ChatEntity
import com.umbra.app.data.media.MediaInfo
import com.umbra.app.data.repo.UiMessage
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class ChatViewModel(
    private val container: AppContainer,
    private val chatId: String,
) : ViewModel() {
    private val repo = container.chatRepository

    val messages: StateFlow<List<UiMessage>> = repo.messagesFor(chatId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Карточка чата: заголовок для TopAppBar (имя контакта или username). */
    val chat: StateFlow<ChatEntity?> = repo.chat(chatId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Таймер самоуничтожения исходящих сообщений, секунды (0 = выключен). */
    val ttlSeconds = MutableStateFlow(0L)

    fun setTtl(seconds: Long) {
        ttlSeconds.value = seconds
    }

    val sending = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** Идёт отправка медиа (чтение + шифрование + загрузка + отправка). */
    private val _sendingMedia = MutableStateFlow(false)
    val sendingMedia: StateFlow<Boolean> = _sendingMedia.asStateFlow()

    /** Последняя ошибка отправки/скачивания медиа; null — всё хорошо. */
    private val _mediaError = MutableStateFlow<String?>(null)
    val mediaError: StateFlow<String?> = _mediaError.asStateFlow()

    fun send(text: String, onQueued: () -> Unit) {
        if (text.isBlank() || sending.value) return
        sending.value = true
        viewModelScope.launch {
            try {
                error.value = null
                repo.sendMessage(chatId, text.trim(), ttlSeconds.value.takeIf { it > 0 })
                onQueued()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.userMessage() }
            finally { sending.value = false }
        }
    }

    /** Отправляет файл/фото из системного пикера как E2E-медиа. */
    fun sendMedia(uri: Uri, caption: String = "") {
        viewModelScope.launch {
            _sendingMedia.value = true
            _mediaError.value = null
            try {
                repo.sendMedia(chatId, uri, caption.trim())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _mediaError.value = e.message ?: "не удалось отправить медиа" }
            finally { _sendingMedia.value = false }
        }
    }

    /** Скачивает и расшифровывает медиа в приватный кэш; файл пригоден для показа/сохранения. */
    suspend fun fetchMedia(info: MediaInfo): File = repo.fetchMediaFile(info)

    fun retry(id: String) {
        viewModelScope.launch {
            try { repo.retry(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.userMessage() }
        }
    }

    fun dismissMediaError() {
        _mediaError.value = null
    }

    class Factory(private val container: AppContainer, private val chatId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(container, chatId) as T
    }
}
