package com.umbra.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.umbra.app.di.AppContainer

/**
 * Авторизация: регистрация по имени и номеру телефона, вход по номеру.
 * Никаких SMS и писем: аккаунт подтверждается ключами устройства
 * (Ed25519 challenge-response), поэтому вход возможен на том устройстве,
 * где создавался аккаунт.
 */
@Composable
fun AuthScreen(container: AppContainer, onAuthed: () -> Unit) {
    val vm: AuthViewModel = viewModel(factory = AuthViewModel.Factory(container))
    val state by vm.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Umbra", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "Зашифрованная переписка. Без SMS и e-mail: ключи — только на вашем устройстве.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))

        TabRow(selectedTabIndex = if (state.mode == AuthMode.REGISTER) 0 else 1) {
            Tab(
                selected = state.mode == AuthMode.REGISTER,
                onClick = { vm.onModeChange(AuthMode.REGISTER) },
                text = { Text("Регистрация") },
            )
            Tab(
                selected = state.mode != AuthMode.REGISTER,
                onClick = { vm.onModeChange(AuthMode.LOGIN) },
                text = { Text("Вход") },
            )
        }
        Spacer(Modifier.height(24.dp))

        when (state.mode) {
            AuthMode.REGISTER -> {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = vm::onNameChange,
                    label = { Text("Ваше имя") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                PhoneField(state.phone, vm::onPhoneChange)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { vm.register() }, enabled = !state.loading) {
                    Text("Создать аккаунт")
                }
            }
            AuthMode.LOGIN, AuthMode.LOGIN_LEGACY -> {
                if (state.mode == AuthMode.LOGIN) {
                    PhoneField(state.phone, vm::onPhoneChange)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { vm.login() }, enabled = !state.loading) {
                        Text("Войти")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { vm.onModeChange(AuthMode.LOGIN_LEGACY) }) {
                        Text("Старый аккаунт: вход по имени")
                    }
                } else {
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = vm::onUsernameChange,
                        label = { Text("Имя пользователя") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { vm.loginLegacy() }, enabled = !state.loading) {
                        Text("Войти по имени")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { vm.onModeChange(AuthMode.LOGIN) }) {
                        Text("Вход по номеру телефона")
                    }
                }
            }
        }

        if (state.loading) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
        }
        state.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        // После успешного входа переключаемся на чаты.
        if (state.authed) onAuthed()
    }
}

@Composable
private fun PhoneField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Номер телефона") },
        placeholder = { Text("+7 999 123-45-67") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
    )
}
