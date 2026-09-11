@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.umbra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umbra.app.data.InputRules
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.data.repo.Conversation
import com.umbra.app.di.AppContainer
import com.umbra.app.ui.theme.LocalUmbraVisuals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MainShell(
    container: AppContainer,
    tab: Int,
    onTab: (Int) -> Unit,
    onOpenChat: (String) -> Unit,
    selectedChatId: String? = null,
    twoPane: Boolean = false,
    onCloseChat: () -> Unit = {},
    sharedChatStateHolder: SaveableStateHolder? = null,
    onCommands: () -> Unit = {},
) {
    val visual = LocalUmbraVisuals.current
    val stateHolder = rememberSaveableStateHolder()
    val localChatStateHolder = rememberSaveableStateHolder()
    val detailStateHolder = sharedChatStateHolder ?: localChatStateHolder
    // Keep the previous destination IDs so an existing saved tab still opens correctly.
    val selectedTab = if (tab == 1) 0 else tab
    val destinations = listOf(
        Triple(0, "Чаты", Icons.Filled.Chat),
        Triple(2, "Звонки", Icons.Filled.Call),
        Triple(3, "Настройки", Icons.Filled.Settings),
    )
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (!twoPane) {
                NavigationBar(containerColor = visual.glassStrong, tonalElevation = 0.dp) {
                    destinations.forEach { (id, label, icon) ->
                        NavigationBarItem(
                            selected = selectedTab == id, onClick = { onTab(id) },
                            icon = { Icon(icon, null) }, label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (twoPane) {
                NavigationRail(containerColor = visual.glassStrong) {
                    Spacer(Modifier.weight(1f))
                    destinations.forEach { (id, label, icon) ->
                        NavigationRailItem(
                            selected = selectedTab == id,
                            onClick = { onTab(id) },
                            icon = { Icon(icon, label) },
                            label = { Text(label) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                SyncBanner(container.chatRepository)
                ScreenEntrance(selectedTab, Modifier.weight(1f).fillMaxWidth()) {
                    stateHolder.SaveableStateProvider(selectedTab) {
                        when (selectedTab) {
                        0 -> if (twoPane) {
                            Row(Modifier.fillMaxSize()) {
                                ChatsTab(
                                    container = container,
                                    initiallyGroups = tab == 1,
                                    onSettings = { onTab(3) },
                                    onOpenChat = onOpenChat,
                                    selectedChatId = selectedChatId,
                                    onCommands = onCommands,
                                    modifier = Modifier.widthIn(min = 320.dp, max = 400.dp).fillMaxHeight(),
                                )
                                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    if (selectedChatId != null) {
                                        detailStateHolder.SaveableStateProvider("chat:$selectedChatId") {
                                            ChatView(container, selectedChatId, onBack = onCloseChat)
                                        }
                                    } else {
                                        AppEmptyState(
                                            icon = Icons.Filled.Chat,
                                            title = "Выберите чат",
                                            description = "Переписка откроется здесь, а список останется рядом.",
                                            modifier = Modifier.align(Alignment.Center),
                                        )
                                    }
                                }
                            }
                        } else ChatsTab(
                            container, initiallyGroups = tab == 1,
                            onSettings = { onTab(3) }, onOpenChat = onOpenChat,
                            onCommands = onCommands,
                        )
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
    // Normal connectivity is not a user-presence status and needs no permanent toolbar.
    if (connected && error == null && !syncing) return
    Surface(color = if (error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                error ?: if (syncing) "Обновление сообщений…" else "Медленный режим: обмен каждые 5 секунд (прямое соединение прервано)",
                Modifier.weight(1f).padding(vertical = 10.dp),
                color = if (error != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            IconButton(onClick = { scope.launch {
                try { repo.refresh(forceFull = true) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* The repository exposes the error in this banner. */ }
            } }, enabled = !syncing) { Icon(Icons.Filled.Refresh, "Обновить сообщения") }
        }
    }
}

@Composable
private fun ChatsTab(
    container: AppContainer,
    initiallyGroups: Boolean,
    onSettings: () -> Unit,
    onOpenChat: (String) -> Unit,
    selectedChatId: String? = null,
    modifier: Modifier = Modifier,
    onCommands: () -> Unit = {},
) {
    val repo = container.chatRepository
    val flow = remember(repo) { repo.conversations() }
    val conversations by flow.collectAsState(emptyList())
    val me by repo.account.collectAsState()
    val syncing by repo.syncing.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(if (initiallyGroups) "groups" else "all") }
    var showNew by rememberSaveable { mutableStateOf(false) }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    val visible = remember(conversations, query, filter) {
        val needle = query.trim()
        conversations.filter { c ->
            (filter == "all" || (filter == "groups") == c.isGroup) &&
                (needle.isEmpty() || c.title.contains(needle, ignoreCase = true) || c.subtitle.contains(needle, ignoreCase = true))
        }
    }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PageHeading("Чаты") {
                IconButton(onClick = onCommands) { Icon(Icons.Filled.Bolt, "Command Center") }
                IconButton(onClick = onSettings, modifier = Modifier.size(48.dp).semantics { contentDescription = "Настройки профиля" }) {
                    Avatar(repo, me.avatarMediaId, me.displayName, 40.dp)
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it.take(160) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                placeholder = { Text("Поиск по чатам") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = if (query.isEmpty()) null else ({ IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Очистить поиск") } }),
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedBorderColor = Color.Transparent,
                ),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "Все", "personal" to "Личные", "groups" to "Группы").forEach { (id, title) ->
                        FilterChip(selected = filter == id, onClick = { filter = id }, label = { Text(title) })
                    }
                }
                IconButton({ showCreate = true }) { Icon(Icons.Filled.GroupAdd, "Создать группу", tint = MaterialTheme.colorScheme.primary) }
            }
            if (visible.isEmpty()) {
                AppEmptyState(
                    icon = if (query.isNotBlank()) Icons.Filled.Search else if (filter == "groups") Icons.Filled.Groups else Icons.Filled.Chat,
                    title = when {
                        query.isNotBlank() -> "Ничего не найдено"
                        syncing -> "Загружаем чаты"
                        filter == "groups" -> "Соберите близких вместе"
                        else -> "Начните разговор"
                    },
                    description = when {
                        query.isNotBlank() -> "Попробуйте другое имя или текст последнего сообщения."
                        syncing -> "Ваши диалоги появятся здесь."
                        filter == "groups" -> "Создайте группу для семейных разговоров."
                        else -> "Напишите близкому человеку по его @никнейму."
                    },
                    modifier = Modifier.weight(1f).padding(bottom = 88.dp),
                )
            } else LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 108.dp),
            ) {
                items(visible, key = { it.chatId }) { c ->
                    ConversationRow(repo, c, selected = c.chatId == selectedChatId) { onOpenChat(c.chatId) }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { if (filter == "groups") showCreate = true else showNew = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            icon = { Icon(if (filter == "groups") Icons.Filled.GroupAdd else Icons.Filled.Edit, null) },
            text = { Text(if (filter == "groups") "Новая группа" else "Написать") },
        )
    }
    if (showCreate) CreateGroupDialog(repo, { showCreate = false }) { id -> showCreate = false; onOpenChat(id) }
    if (showNew) NewConversationSheet(repo, conversations.filter { !it.isGroup }, { showNew = false }) { id ->
        showNew = false
        onOpenChat(id)
    }
}

@Composable
private fun ConversationRow(repo: ChatRepository, c: Conversation, selected: Boolean, onClick: () -> Unit) {
    val visual = LocalUmbraVisuals.current
    val interaction = rememberFutureInteraction()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(if (selected) visual.auraPrimary.copy(alpha = 0.16f) else Color.Transparent)
        .futurePress(interaction)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (c.isGroup) GroupAvatar(c.title) else UserAvatar(repo, c.chatId, c.title, 52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.lastAtMillis > 0) Text(shortDate(c.lastAtMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(c.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (c.unreadCount > 0) Badge { Text(c.unreadCount.coerceAtMost(99).toString()) }
    }
}

@Composable
private fun NewConversationSheet(repo: ChatRepository, people: List<Conversation>, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy || it != SheetValue.Hidden })
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Кому написать?", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                username, { username = it.take(33); error = null }, Modifier.fillMaxWidth(),
                label = { Text("@никнейм") }, enabled = !busy, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrect = false),
                shape = RoundedCornerShape(18.dp),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val user = repo.resolveByUsername(InputRules.username(username)) ?: throw IllegalArgumentException("Пользователь не найден. Проверьте никнейм.")
                        repo.openDm(user.id)
                        onOpen(user.id)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.userMessage() }
                    finally { busy = false }
                }
            }, enabled = !busy && InputRules.validUsername(InputRules.username(username)), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (busy) "Открываем…" else "Начать разговор")
            }
            if (people.isNotEmpty()) {
                Text("Ваши собеседники", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                people.forEach { person ->
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = !busy) { onOpen(person.chatId) }
                        .padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        UserAvatar(repo, person.chatId, person.title, 44.dp)
                        Text(person.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
