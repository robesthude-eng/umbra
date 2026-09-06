package com.umbra.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.umbra.app.di.AppContainer

/** Корневой экран: авторизация → список чатов. */
@Composable
fun UmbraRoot(container: AppContainer) {
    val repo = container.chatRepository
    var loggedIn by remember { mutableStateOf(repo.isLoggedIn()) }

    if (loggedIn) {
        ChatsScreen(container)
    } else {
        AuthScreen(container, onAuthed = { loggedIn = true })
    }
}

@Composable
fun AuthScreen(container: AppContainer, onAuthed: () -> Unit) {
    val vm: AuthViewModel = viewModel(factory = AuthViewModel.Factory(container))
    val state by vm.state

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Umbra", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "Зашифрованная переписка. Ключи — только на вашем устройстве.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = state.username,
            onValueChange = vm::onUsernameChange,
            label = { Text("Имя пользователя") },
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))

        Button(onClick = { vm.register() }, enabled = !state.loading) {
            Text("Создать аккаунт")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.login() }, enabled = !state.loading) {
            Text("Войти")
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
