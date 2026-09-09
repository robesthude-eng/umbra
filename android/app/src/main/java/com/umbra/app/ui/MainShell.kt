package com.umbra.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umbra.app.data.InputRules
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.di.AppContainer
import com.umbra.app.ui.theme.UmbraColors
import kotlinx.coroutines.launch

@Composable
fun MainShell(container: AppContainer, tab: Int, onTab: (Int) -> Unit, onOpenChat: (String) -> Unit) {
    val stateHolder = rememberSaveableStateHolder()
    Scaffold(containerColor = Color.Transparent, bottomBar = {
        NavigationBar {
            val labels = listOf("Чаты", "Группы", "Звонки", "Настройки")
            val icons = listOf(Icons.Filled.Chat, Icons.Filled.Groups, Icons.Filled.Call, Icons.Filled.Settings)
            labels.forEachIndexed { index, label ->
                NavigationBarItem(tab == index, { onTab(index) }, icon = { Icon(icons[index], null) }, label = { Text(label) })
            }
        }
    }) { padding ->
        GlassBackground {
            Column(Modifier.fillMaxSize().padding(padding)) {
                SyncBanner(container.chatRepository)
                Box(Modifier.weight(1f)) {
                    stateHolder.SaveableStateProvider(tab) {
                        when (tab) {
                            0 -> ChatsTab(container, onOpenChat)
                            1 -> GroupsTab(container, onOpenChat)
                            2 -> CallsTab(container)
                            else -> SettingsTab(container)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SyncBanner(repo: ChatRepository) {
    val connected by repo.connected.collectAsState()
    val error by repo.syncError.collectAsState()
    val syncing by repo.syncing.collectAsState()
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(error ?: if (syncing) "Обновление…" else if (connected) "Подключено" else "Подключение к серверу…",
            Modifier.weight(1f), color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        IconButton(onClick = { scope.launch {
            try { repo.refresh(forceFull = true) }
            catch (e: Exception) { e.userMessage() /* The repository exposes the error above. */ }
        } }, enabled = !syncing) { Icon(Icons.Filled.Refresh, "Обновить", tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun TabHeading(title: String, addLabel: String? = null, onAdd: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (onAdd != null) IconButton(onAdd) { Icon(Icons.Filled.Add, addLabel, tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun ChatsTab(container: AppContainer, onOpenChat: (String) -> Unit) {
    val repo = container.chatRepository
    val flow = remember(repo) { repo.conversations() }
    val conversations by flow.collectAsState(emptyList())
    val syncing by repo.syncing.collectAsState()
    var showNew by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TabHeading("Чаты", "Новый чат") { showNew = true }
        if (conversations.isEmpty()) EmptyState(if (syncing) "Загружаем диалоги…" else "Пока нет диалогов. Нажмите «+», чтобы написать по @никнейму.")
        else LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(conversations, key = { it.chatId }) { c ->
                GlassRow({ onOpenChat(c.chatId) }) {
                    if (c.isGroup) Avatar(repo, null, c.title, 44.dp) else UserAvatar(repo, c.chatId, c.title, 44.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (c.lastAtMillis > 0) Text(shortDate(c.lastAtMillis), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (showNew) UsernameDialog("Новый диалог", "Введите никнейм собеседника.", "Начать", onDismiss = { showNew = false }) { username ->
        val user = repo.resolveByUsername(username) ?: throw IllegalArgumentException("Пользователь не найден. Проверьте никнейм.")
        repo.openDm(user.id)
        showNew = false
        onOpenChat(user.id)
    }
}

@Composable
private fun GroupsTab(container: AppContainer, onOpenChat: (String) -> Unit) {
    val repo = container.chatRepository
    val flow = remember(repo) { repo.chats() }
    val chats by flow.collectAsState(emptyList())
    val groups = chats.filter { it.type != "dm" }
    val syncing by repo.syncing.collectAsState()
    var showCreate by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TabHeading("Группы", "Создать группу") { showCreate = true }
        if (groups.isEmpty()) EmptyState(if (syncing) "Загружаем группы…" else "Создайте группу и пригласите близких по @никнеймам.")
        else LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups, key = { it.id }) { group ->
                GlassRow({ onOpenChat(group.id) }) {
                    Avatar(repo, null, group.title, 44.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(group.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (showCreate) CreateGroupDialog(repo, { showCreate = false }) { id -> showCreate = false; onOpenChat(id) }
}

@Composable
private fun CreateGroupDialog(repo: ChatRepository, onDismiss: () -> Unit, onOpenChat: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var members by rememberSaveable { mutableStateOf("") }
    var createdId by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (createdId == null) "Новая группа" else "Приглашение участников") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (createdId != null) Text("Группа уже создана. Повторите приглашение: новая группа при этом не создаётся.")
            OutlinedTextField(title, { title = it.take(100); error = null }, enabled = !busy && createdId == null, label = { Text("Название *") }, singleLine = true)
            OutlinedTextField(members, { members = it.take(1000); error = null }, enabled = !busy, label = { Text("@никнеймы через запятую") }, maxLines = 4)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        TextButton(enabled = !busy && title.isNotBlank(), onClick = {
            if (!busy) {
                busy = true; error = null
                scope.launch {
                    try {
                        val names = members.split(',', '\n').map(InputRules::username).filter { it.isNotBlank() }.distinct()
                        val ids = names.map { name ->
                            (repo.resolveByUsername(name) ?: throw IllegalArgumentException("@$name не найден. Проверьте никнейм.")).id
                        }
                        val id = createdId ?: repo.createGroup(title).also { createdId = it }
                        repo.addGroupMembers(id, ids)
                        onOpenChat(id)
                    } catch (e: Exception) { error = e.userMessage() }
                    finally { busy = false }
                }
            }
        }) { Text(if (createdId == null) "Создать" else "Повторить") }
    }, dismissButton = {
        TextButton(onClick = { createdId?.let(onOpenChat) ?: onDismiss() }, enabled = !busy) { Text(if (createdId == null) "Отмена" else "Открыть группу") }
    })
}

@Composable
private fun CallsTab(container: AppContainer) {
    val repo = container.chatRepository
    val calls by repo.calls.collectAsState()
    val active by repo.activeCall.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCall by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun reload() {
        loading = true; error = null
        try { repo.fetchCalls() }
        catch (e: Exception) { error = e.userMessage() }
        finally { loading = false }
    }
    LaunchedEffect(active) { reload() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TabHeading("Звонки")
        Text("Звук и видео пока недоступны. Можно проверить только отправку вызова.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton({ showCall = true }, enabled = active == null) { Text("Проверить вызов без звука") }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { problem ->
            Text(problem, color = MaterialTheme.colorScheme.error)
            TextButton({ scope.launch { reload() } }, enabled = !loading) { Text("Повторить загрузку") }
        }
        if (calls.isEmpty() && !loading && error == null) EmptyState("Вызовов пока не было.")
        else LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(calls, key = { it.id }) { call ->
                GlassRow(null) {
                    UserAvatar(repo, call.peerUserId, call.peerName, 44.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text((if (call.incoming) "Входящий: " else "Исходящий: ") + call.peerName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(callStatusText(call.status) + " · " + shortDate(call.createdAtMillis), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (showCall) UsernameDialog("Проверка вызова", "Собеседник увидит вызов, если приложение открыто. Звука и видео не будет.", "Отправить вызов", { showCall = false }) { name ->
        val user = repo.resolveByUsername(name) ?: throw IllegalArgumentException("Пользователь не найден")
        repo.startCall(user.id)
        showCall = false
    }
}

internal fun callStatusText(status: String) = when (status) {
    "ringing" -> "Вызов отправлен"
    "active" -> "Вызов принят"
    "ended" -> "Завершён"
    "declined" -> "Отклонён"
    "missed" -> "Пропущен"
    else -> "Статус неизвестен"
}

@Composable
private fun SettingsTab(container: AppContainer) {
    val repo = container.chatRepository
    val me by repo.account.collectAsState()
    val scope = rememberCoroutineScope()
    var editProfile by rememberSaveable { mutableStateOf(false) }
    var confirmBurn by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && !busy) {
            busy = true; error = null
            scope.launch {
                try { repo.uploadAndSetAvatar(uri) }
                catch (e: Exception) { error = e.userMessage() }
                finally { busy = false }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TabHeading("Настройки")
        GlassRow(if (busy) null else ({ editProfile = true })) {
            Avatar(repo, me.avatarMediaId, me.displayName, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(listOf(me.displayName, me.lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Без имени" }, fontWeight = FontWeight.SemiBold)
                Text("@${me.username}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(me.phone, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedButton({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Сменить аватар") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Сообщения и фото хранятся на сервере владельца Umbra. Сквозное шифрование в этой версии не используется.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
        Button({ confirmLogout = true }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Выйти") }
        TextButton({ error = null; confirmBurn = true }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Удалить аккаунт", color = MaterialTheme.colorScheme.error) }
    }
    if (editProfile) {
        var name by rememberSaveable { mutableStateOf(me.displayName) }
        var lastName by rememberSaveable { mutableStateOf(me.lastName) }
        var username by rememberSaveable { mutableStateOf(me.username) }
        var saving by remember { mutableStateOf(false) }
        var problem by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { if (!saving) editProfile = false }, title = { Text("Мой профиль") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileFields(name, lastName, username, { name = it; problem = null }, { lastName = it; problem = null }, { username = it; problem = null }, !saving)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = {
            TextButton(enabled = !saving && name.isNotBlank() && InputRules.validUsername(username), onClick = {
                if (!saving) {
                    saving = true; problem = null
                    scope.launch {
                        try { repo.updateProfile(name, lastName, username); editProfile = false }
                        catch (e: Exception) { problem = e.userMessage() }
                        finally { saving = false }
                    }
                }
            }) { Text("Сохранить") }
        }, dismissButton = { TextButton({ editProfile = false }, enabled = !saving) { Text("Отмена") } })
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("Выйти из аккаунта?") },
        text = { Text("Для входа понадобится новый код. Неотправленные сообщения сохранятся на этом устройстве для этого номера. При входе в другой аккаунт локальные данные будут очищены.") },
        confirmButton = { TextButton({ confirmLogout = false; scope.launch { repo.logout() } }) { Text("Выйти") } },
        dismissButton = { TextButton({ confirmLogout = false }) { Text("Отмена") } })
    if (confirmBurn) AlertDialog(onDismissRequest = { if (!busy) confirmBurn = false }, title = { Text("Удалить аккаунт навсегда?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Будут удалены аккаунт, его сообщения, созданные вами группы и локальная история. Файлы будут поставлены в очередь удаления. Чужие копии и резервные копии могут сохраниться.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } }, confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (!busy) {
                    busy = true; error = null
                    scope.launch {
                        try { repo.deleteAccount() }
                        catch (e: Exception) { error = "Удаление не подтверждено. " + e.userMessage() }
                        finally { busy = false }
                    }
                }
            }) { Text("Удалить навсегда", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton({ confirmBurn = false }, enabled = !busy) { Text("Отмена") } })
}

@Composable
internal fun UsernameDialog(title: String, description: String, action: String, onDismiss: () -> Unit, onSubmit: suspend (String) -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(description)
            OutlinedTextField(username, { username = it.take(33); error = null }, enabled = !busy, label = { Text("@никнейм") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        TextButton(enabled = !busy && username.isNotBlank(), onClick = {
            if (!busy) {
                busy = true; error = null
                scope.launch {
                    try { onSubmit(username) }
                    catch (e: Exception) { error = e.userMessage() }
                    finally { busy = false }
                }
            }
        }) { Text(action) }
    }, dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("Отмена") } })
}

@Composable
fun GlassBackground(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface.copy(alpha = 1f)))), content = content)
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
internal fun GlassRow(onClick: (() -> Unit)?, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

internal fun shortDate(millis: Long): String = if (millis <= 0) "" else
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))
