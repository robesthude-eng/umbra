package com.umbra.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.umbra.app.data.db.ChatEntity
import com.umbra.app.data.repo.CallUi
import com.umbra.app.data.repo.Conversation
import com.umbra.app.data.repo.SessionPhase
import com.umbra.app.di.AppContainer
import com.umbra.app.ui.theme.UmbraColors
import kotlinx.coroutines.launch

/** Основной экран приложения с нижним таб-баром Telegram-style. */
@Composable
fun MainShell(container: AppContainer, onOpenChat: (String) -> Unit) {
    val repo = container.chatRepository
    var tab by rememberSaveable { mutableStateOf(0) }

    // Уведомления о входящем звонке собираются в корневом экране (UmbraRoot).
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Chat, null) },
                    label = { Text("Чаты", style = MaterialTheme.typography.labelSmall) },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Groups, null) },
                    label = { Text("Группы", style = MaterialTheme.typography.labelSmall) },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Call, null) },
                    label = { Text("Звонки", style = MaterialTheme.typography.labelSmall) },
                )
                NavigationBarItem(
                    selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Settings, null) },
                    label = { Text("Настройки", style = MaterialTheme.typography.labelSmall) },
                )
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> ChatsTab(container, onOpenChat)
                1 -> GroupsTab(container, onOpenChat)
                2 -> CallsTab(container)
                3 -> SettingsTab(container)
            }
        }
    }
}

@Composable
private fun TabTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = UmbraColors.Ice, fontWeight = FontWeight.SemiBold)
}

// ================= Чаты =================

@Composable
private fun ChatsTab(container: AppContainer, onOpenChat: (String) -> Unit) {
    val repo = container.chatRepository
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var showNewDm by remember { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { repo.conversations().collect { conversations = it } }

    GlassBackground {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabTitle("Чаты")
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showNewDm = true }) { Icon(Icons.Filled.Add, "Новый чат", tint = UmbraColors.Aqua) }
            }
            if (conversations.isEmpty()) {
                EmptyState("Пока нет диалогов. Нажмите «+», чтобы написать по @никнейму.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(conversations, key = { it.chatId }) { c ->
                        GlassRow(onClick = { onOpenChat(c.chatId) }) {
                            Column(Modifier.weight(1f)) {
                                Text(c.title, color = UmbraColors.Ice, fontWeight = FontWeight.Medium)
                                Text(c.subtitle, color = UmbraColors.Fog, maxLines = 1)
                            }
                            if (c.lastAtMillis > 0) Text(shortDate(c.lastAtMillis), color = UmbraColors.Fog)
                        }
                    }
                }
            }
        }
    }

    if (showNewDm) {
        AlertDialog(
            onDismissRequest = { showNewDm = false },
            title = { Text("Новый диалог") },
            text = {
                Column {
                    OutlinedTextField(username, { username = it },
                        label = { Text("@никнейм собеседника") }, singleLine = true)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && username.isNotBlank(), onClick = {
                    scope.launch {
                        busy = true; error = null
                        val card = repo.resolveByUsername(username)
                        if (card == null) { error = "Пользователь не найден"; busy = false; return@launch }
                        repo.openDm(card.id)
                        showNewDm = false
                        onOpenChat(card.id)
                    }
                }) { Text("Начать") }
            },
            dismissButton = { TextButton({ showNewDm = false }) { Text("Отмена") } },
        )
    }
}

// ================= Группы =================

@Composable
private fun GroupsTab(container: AppContainer, onOpenChat: (String) -> Unit) {
    val repo = container.chatRepository
    var groups by remember { mutableStateOf<List<ChatEntity>>(emptyList()) }
    var showCreate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { repo.chats().collect { groups = it.filter { g -> g.type == "group" } } }

    GlassBackground {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabTitle("Группы")
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showCreate = true }) { Icon(Icons.Filled.Add, "Создать группу", tint = UmbraColors.Aqua) }
            }
            if (groups.isEmpty()) {
                EmptyState("Групп пока нет. Создайте группу для всей семьи — сообщения увидят все участники.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(groups, key = { it.id }) { g ->
                        GlassRow(onClick = { onOpenChat(g.id) }) {
                            Column { Text(g.title, color = UmbraColors.Ice); Text("группа", color = UmbraColors.Fog) }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        var title by rememberSaveable { mutableStateOf("") }
        var members by rememberSaveable { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Новая группа") },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(members, { members = it },
                        label = { Text("@никнеймы участников через запятую") })
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && title.isNotBlank(), onClick = {
                    scope.launch {
                        busy = true; err = null
                        val ids = members.split(',', '\n').map { it.trim().removePrefix("@") }
                            .filter { it.isNotBlank() }
                        val resolved = ArrayList<String>()
                        for (u in ids) repo.resolveByUsername(u)?.let { resolved.add(it.id) }
                        if (ids.isNotEmpty() && resolved.size != ids.size) { err = "Не все участники найдены"; busy = false; return@launch }
                        repo.createGroup(title.trim(), resolved)
                        showCreate = false
                    }
                }) { Text("Создать") }
            },
            dismissButton = { TextButton({ showCreate = false }) { Text("Отмена") } },
        )
    }
}

// ================= Звонки =================

@Composable
private fun CallsTab(container: AppContainer) {
    val repo = container.chatRepository
    var calls by remember { mutableStateOf<List<CallUi>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var showCallDlg by remember { mutableStateOf(false) }

    suspend fun reload() { calls = repo.fetchCalls() }
    LaunchedEffect(Unit) { reload() }

    GlassBackground {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabTitle("Звонки")
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showCallDlg = true }) { Icon(Icons.Filled.Add, "Позвонить", tint = UmbraColors.Aqua) }
            }
            Text("Аудио/видео-медиа появится в следующей версии: сейчас сервер передаёт сигнал вызова.",
                color = UmbraColors.Fog, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            if (calls.isEmpty()) {
                EmptyState("Звонков пока не было.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(calls, key = { it.id }) { c ->
                        GlassRow(onClick = null) {
                            Column {
                                Text(if (c.incoming) "Входящий: " else "Исходящий: " + c.peerName,
                                    color = UmbraColors.Ice)
                                Text(c.status + " · " + shortDate(c.createdAtMillis), color = UmbraColors.Fog)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCallDlg) {
        var username by rememberSaveable { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showCallDlg = false },
            title = { Text("Позвонить") },
            text = {
                Column {
                    Text("Наберите @никнейм. Собеседнику придёт уведомление о входящем вызове.")
                    OutlinedTextField(username, { username = it }, label = { Text("@никнейм") }, singleLine = true)
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = username.isNotBlank(), onClick = {
                    scope.launch {
                        val card = repo.resolveByUsername(username.trim())
                        if (card == null) { err = "Пользователь не найден"; return@launch }
                        repo.startCall(card.id)
                        showCallDlg = false
                        reload()
                    }
                }) { Text("Вызвать") }
            },
            dismissButton = { TextButton({ showCallDlg = false }) { Text("Отмена") } },
        )
    }
}

// ================= Настройки =================

@Composable
private fun SettingsTab(container: AppContainer) {
    val repo = container.chatRepository
    val scope = rememberCoroutineScope()
    val me = repo.accountInfo()
    val connected by repo.connected.collectAsState()
    val syncError by repo.syncError.collectAsState()
    var editProfile by remember { mutableStateOf(false) }
    var confirmBurn by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { runCatching { repo.uploadAndSetAvatar(uri) } }
    }

    GlassBackground {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TabTitle("Настройки")
            GlassRow(onClick = { editProfile = true }) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(UmbraColors.headerGradient),
                    contentAlignment = Alignment.Center) {
                    Text(me.displayName.take(1).ifBlank { "?" }, color = Color.White, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text((me.displayName + " " + me.lastName).trim().ifBlank { "Без имени" },
                        color = UmbraColors.Ice, fontWeight = FontWeight.SemiBold)
                    Text("@" + me.username + " · " + me.phone, color = UmbraColors.Fog)
                }
            }
            GlassRow(onClick = {
                pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Сменить аватар", color = UmbraColors.Ice) }

            val status = when {
                busy -> "Синхронизация…"
                syncError != null -> "Нет связи с сервером"
                else -> if (connected) "Облако: подключено" else "Облако: подключение…"
            }
            Text(status, color = UmbraColors.Fog, style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.weight(1f))
            Button(onClick = { scope.launch { busy = true; repo.logout() } },
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A5A70))) {
                Text("Выйти")
            }
            TextButton(onClick = { confirmBurn = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Удалить аккаунт", color = UmbraColors.Danger)
            }
        }
    }

    if (editProfile) {
        var name by rememberSaveable { mutableStateOf(me.displayName) }
        var lastName by rememberSaveable { mutableStateOf(me.lastName) }
        var username by rememberSaveable { mutableStateOf(me.username) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editProfile = false },
            title = { Text("Мой профиль") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Имя *") }, singleLine = true)
                    OutlinedTextField(lastName, { lastName = it }, label = { Text("Фамилия") }, singleLine = true)
                    OutlinedTextField(username, { username = it }, label = { Text("@никнейм") }, singleLine = true)
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch {
                        try { repo.updateProfile(name.trim(), lastName.trim(), username.trim()) }
                        catch (e: Exception) { err = e.userMessage() }
                        finally { if (err == null) editProfile = false }
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton({ editProfile = false }) { Text("Отмена") } },
        )
    }
    if (confirmBurn) {
        AlertDialog(
            onDismissRequest = { confirmBurn = false },
            title = { Text("Удалить аккаунт?") },
            text = { Text("Все сообщения, группы и файлы на сервере будут удалены безвозвратно.") },
            confirmButton = {
                TextButton(onClick = { confirmBurn = false; scope.launch { repo.deleteAccount() } }) {
                    Text("Удалить навсегда", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ confirmBurn = false }) { Text("Отмена") } },
        )
    }
}

// ================= общие элементы =================

@Composable
fun GlassBackground(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0E2437), Color(0xFF0A1A28)))),
        content = content,
    )
}

@Composable
private fun EmptyState(text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = UmbraColors.Fog, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun GlassRow(onClick: (() -> Unit)?, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(Color(0x33000000))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private fun shortDate(millis: Long): String {
    if (millis <= 0) return ""
    return java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))
}
