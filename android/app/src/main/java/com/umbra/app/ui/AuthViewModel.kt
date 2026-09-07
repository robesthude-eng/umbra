package com.umbra.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

data class AuthState(
    val username: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val authed: Boolean = false,
)

class AuthViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AuthState(username = container.cryptoManager.username().orEmpty()))
    val state: StateFlow<AuthState> = _state

    fun onUsernameChange(v: String) = _state.update { it.copy(username = v.trim()) }

    fun register() = run {
        val name = _state.value.username
        container.chatRepository.register(name)
    }

    fun login() = run {
        container.chatRepository.login(_state.value.username)
    }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.loading) return
        if (_state.value.username.isBlank()) {
            _state.update { it.copy(error = "Введите имя пользователя") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                block()
                _state.update { it.copy(loading = false, authed = true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AuthViewModel(container) as T
    }
}
