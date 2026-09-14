package com.umbra.app.data.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Черновики сообщений: недописанный текст переживает выход из чата
 * и перезапуск приложения. Храним отдельно от базы: это не сообщения,
 * а состояние интерфейса, и очистка истории их не трогает.
 */
class Drafts(context: Context) {
    private val prefs = context.getSharedPreferences("umbra_drafts", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())

    /** Ключ — id чата, значение — текст черновика. */
    val state: StateFlow<Map<String, String>> = _state.asStateFlow()

    private fun load(): Map<String, String> = prefs.all.entries
        .mapNotNull { entry -> (entry.value as? String)?.takeIf { it.isNotBlank() }?.let { entry.key to it } }
        .toMap()

    fun get(chatId: String): String = _state.value[chatId].orEmpty()

    fun put(chatId: String, text: String) {
        if (chatId.isBlank()) return
        val trimmed = text.take(MAX_LENGTH)
        if (trimmed.isBlank()) {
            clear(chatId)
            return
        }
        if (_state.value[chatId] == trimmed) return
        prefs.edit().putString(chatId, trimmed).apply()
        _state.update { it + (chatId to trimmed) }
    }

    fun clear(chatId: String) {
        if (_state.value[chatId] == null) return
        prefs.edit().remove(chatId).apply()
        _state.update { it - chatId }
    }

    /** Выход из аккаунта не должен оставлять чужие черновики. */
    fun clearAll() {
        prefs.edit().clear().apply()
        _state.value = emptyMap()
    }

    companion object {
        const val MAX_LENGTH = 16_000
    }
}
