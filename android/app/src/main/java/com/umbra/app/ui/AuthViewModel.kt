package com.umbra.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthState(
    val username: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val authed: Boolean = false,
)

class AuthViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state

    fun onUsernameChange(v: String) = _state.update { it.copy(username = v.trim()) }

    fun register() = run("register") {
        val name = _state.value.username
        container.chatRepository.register(name)
        // Регистрация не выдаёт токен — сразу входим для получения сессии.
        container.chatRepository.login(name)
    }

    fun login() = run("login") {
        container.chatRepository.login(_state.value.username)
    }

    private fun run(tag: String, block: suspend () -> Unit) {
        if (_state.value.username.isBlank()) {
            _state.update { it.copy(error = "Введите имя пользователя") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { block() }
                .onSuccess { _state.update { it.copy(loading = false, authed = true) } }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, error = "Ошибка $tag: ${e.message}") }
                }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AuthViewModel(container) as T
    }
}
