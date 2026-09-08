package com.umbra.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.umbra.app.data.db.ChatEntity
import com.umbra.app.di.AppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    container: AppContainer,
    onOpenChat: (String) -> Unit,
    onOpenContacts: () -> Unit,
    onLogout: () -> Unit,
) {
    val vm: ChatsViewModel = viewModel(factory = ChatsViewModel.Factory(container))
    val chats by vm.chats.collectAsState()
    val newChat by vm.newChatState.collectAsState()
    val connected by container.chatRepository.connected.collectAsState()
    val syncError by container.chatRepository.syncError.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var showBurnConfirm by remember { mutableStateOf(false) }

    // Обработка результата диалога «новый чат».
    LaunchedEffect(newChat) {
        (newChat as? ChatsViewModel.NewChatState.Started)?.let { started ->
            vm.hideNewChatDialog()
            onOpenChat(started.chat.id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Чаты")
                        val status = syncError ?: if (!connected) "Подключение…" else null
                        status?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                },
                actions = {
                    Icon(Icons.Filled.Lock, contentDescription = "E2E", tint = MaterialTheme.colorScheme.secondary)
                    IconButton(onClick = onOpenContacts) {
                        Icon(Icons.Filled.Person, contentDescription = "Контакты")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Меню")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Выйти") },
                            onClick = {
                                menuOpen = false
                                onLogout()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить аккаунт", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuOpen = false
                                showBurnConfirm = true
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.showNewChatDialog() }) {
                Icon(Icons.Filled.Add, contentDescription = "Новый чат")
            }
        },
    ) { padding ->
        if (chats.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Пока нет чатов", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Откройте «Контакты», чтобы написать тем, кто уже в Umbra, " +
                        "или нажмите «+» для диалога по имени пользователя.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(chats, key = { it.id }) { chat ->
                    ChatRow(chat, onClick = { onOpenChat(chat.id) })
                }
            }
        }
    }

    // Диалог нового чата.
    if (newChat != ChatsViewModel.NewChatState.Hidden) {
        NewChatDialog(vm)
    }

    // Подтверждение полного удаления аккаунта.
    if (showBurnConfirm) {
        AlertDialog(
            onDismissRequest = { showBurnConfirm = false },
            title = { Text("Удалить аккаунт?") },
            text = {
                Text("Аккаунт, ключи, сообщения, медиа и контакты будут удалены безвозвратно — и на сервере, и на этом устройстве.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showBurnConfirm = false
                    vm.deleteAccount()
                }) { Text("Удалить навсегда", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBurnConfirm = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun ChatRow(chat: ChatEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text(
                text = chat.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun NewChatDialog(vm: ChatsViewModel) {
    var username by remember { mutableStateOf("") }
    val loading = vm.newChatState.collectAsState().value == ChatsViewModel.NewChatState.Loading
    val error = (vm.newChatState.collectAsState().value as? ChatsViewModel.NewChatState.Error)?.message

    AlertDialog(
        onDismissRequest = { vm.hideNewChatDialog() },
        title = { Text("Новый диалог") },
        text = {
            Column {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Имя пользователя") },
                    singleLine = true,
                )
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.startChat(username) }, enabled = !loading) {
                Text("Начать")
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.hideNewChatDialog() }) { Text("Отмена") }
        },
    )
}
