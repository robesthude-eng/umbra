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

/** Режим экрана авторизации: регистрация, вход по номеру, вход старого аккаунта. */
enum class AuthMode { REGISTER, LOGIN, LOGIN_LEGACY }

data class AuthState(
    val mode: AuthMode = AuthMode.REGISTER,
    val name: String = "",
    val phone: String = "",
    val username: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val authed: Boolean = false,
)

class AuthViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(
        AuthState(
            phone = container.cryptoManager.phone().orEmpty(),
            username = container.cryptoManager.username().orEmpty(),
            // Устройство с сохранёнными ключами, но без сессии — это вход, не регистрация.
            mode = if (container.cryptoManager.hasIdentity()) AuthMode.LOGIN else AuthMode.REGISTER,
        )
    )
    val state: StateFlow<AuthState> = _state

    fun onModeChange(mode: AuthMode) = _state.update { it.copy(mode = mode, error = null) }
    fun onNameChange(v: String) = _state.update { it.copy(name = v.take(64)) }
    fun onPhoneChange(v: String) = _state.update { it.copy(phone = v.take(24)) }
    fun onUsernameChange(v: String) = _state.update { it.copy(username = v.trim()) }

    /** Регистрация по имени и номеру телефона (без SMS/писем — ключи устройства). */
    fun register() = run {
        val s = _state.value
        container.chatRepository.register(s.name, s.phone)
    }

    /** Вход по номеру телефона (ключи аккаунта должны быть на устройстве). */
    fun login() = run {
        container.chatRepository.login(_state.value.phone)
    }

    /** Вход старого аккаунта по имени пользователя. */
    fun loginLegacy() = run {
        container.chatRepository.loginLegacy(_state.value.username)
    }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.loading) return
        val s = _state.value
        val inputError = when (s.mode) {
            AuthMode.REGISTER -> when {
                s.name.isBlank() -> "Введите имя"
                s.phone.isBlank() -> "Введите номер телефона"
                else -> null
            }
            AuthMode.LOGIN -> if (s.phone.isBlank()) "Введите номер телефона" else null
            AuthMode.LOGIN_LEGACY -> if (s.username.isBlank()) "Введите имя пользователя" else null
        }
        if (inputError != null) {
            _state.update { it.copy(error = inputError) }
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
