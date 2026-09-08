package com.umbra.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.umbra.app.data.db.ContactEntity
import com.umbra.app.di.AppContainer

/**
 * Телефонная книга: кто из контактов уже в Umbra — тому можно написать.
 * На сервер уходят только SHA-256-хэши номеров, не сами номера.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    container: AppContainer,
    onOpenChat: (String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: ContactsViewModel = viewModel(factory = ContactsViewModel.Factory(container))
    val contacts by vm.contacts.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val error by vm.error.collectAsState()
    val startedChatId by vm.startedChatId.collectAsState()

    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted = allowed
        if (allowed) vm.sync()
    }

    // Первая синхронизация при входе на экран, если разрешение уже есть.
    LaunchedEffect(granted) {
        if (granted && contacts.isEmpty()) vm.sync()
    }
    // Диалог создан — открываем его.
    LaunchedEffect(startedChatId) {
        startedChatId?.let {
            vm.consumeStartedChat()
            onOpenChat(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Контакты") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (granted) {
                        IconButton(onClick = { vm.sync() }, enabled = !syncing) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (!granted) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Чтобы найти друзей в Umbra, разрешите доступ к контактам. " +
                        "Номера не покидают устройство в открытом виде: на сервер " +
                        "отправляются только их хэши.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) },
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text("Разрешить доступ к контактам")
                }
            }
            return@Scaffold
        }

        val registered = contacts.filter { it.umbraUserId != null }
        val others = contacts.filter { it.umbraUserId == null }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (syncing) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
            }
            error?.let {
                item {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (registered.isNotEmpty()) {
                item { SectionHeader("Уже в Umbra — можно написать") }
                items(registered, key = { it.phoneHash }) { contact ->
                    ContactRow(contact, onClick = { vm.startChat(contact) })
                }
            }
            if (others.isNotEmpty()) {
                item { SectionHeader("Ещё не в Umbra") }
                items(others, key = { it.phoneHash }) { contact ->
                    ContactRow(contact, onClick = null)
                }
            }
            if (!syncing && contacts.isEmpty() && error == null) {
                item {
                    Text(
                        "Телефонная книга пуста или контакты не найдены.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ContactRow(contact: ContactEntity, onClick: (() -> Unit)?) {
    val clickable = onClick != null
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(enabled = clickable) { onClick?.invoke() },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = if (clickable) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    contact.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (clickable) contact.phone
                    else contact.phone + " · ещё не зарегистрирован",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
