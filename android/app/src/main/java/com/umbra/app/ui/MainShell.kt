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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.umbra.app.ui.theme.LocalUmbraSmokedGlass
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
    val smokedGlass = LocalUmbraSmokedGlass.current
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
                NavigationBar(
                    // Светящаяся кромка сверху появляется только в Alien-режиме.
                    modifier = if (smokedGlass) Modifier.navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .smokedGlassSurface(RoundedCornerShape(32.dp), strong = true)
                        else Modifier.alienTopEdge(),
                    containerColor = if (smokedGlass) Color.Transparent else visual.glassStrong,
                    windowInsets = if (smokedGlass) WindowInsets(0, 0, 0, 0) else NavigationBarDefaults.windowInsets,
                    tonalElevation = 0.dp,
                ) {
                    destinations.forEach { (id, label, icon) ->
                        NavigationBarItem(
                            selected = selectedTab == id, onClick = { onTab(id) },
                            icon = { OrbitalNavIcon(icon, label, selectedTab == id) },
                            label = { AlienAwareLabel(label) },
                            colors = if (smokedGlass) NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            ) else NavigationBarItemDefaults.colors(),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (twoPane) {
                NavigationRail(
                    modifier = if (smokedGlass) Modifier.smokedGlassSurface(RoundedCornerShape(26.dp), strong = true) else Modifier,
                    containerColor = if (smokedGlass) Color.Transparent else visual.glassStrong,
                ) {
                    Spacer(Modifier.weight(1f))
                    destinations.forEach { (id, label, icon) ->
                        NavigationRailItem(
                            selected = selectedTab == id,
                            onClick = { onTab(id) },
                            icon = { OrbitalNavIcon(icon, label, selectedTab == id) },
                            label = { AlienAwareLabel(label) },
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
    val tokens = com.umbra.app.ui.theme.LocalUmbraAlienTokens.current
    val alien = tokens.enabled
    val visual = LocalUmbraVisuals.current
    val scope = rememberCoroutineScope()
    // Normal connectivity is not a user-presence status and needs no permanent toolbar.
    if (connected && error == null && !syncing) return
    Surface(color = if (error != null) MaterialTheme.colorScheme.errorContainer
        else if (alien) visual.auraPrimary.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Деления сигнала: ошибка — одно, обмен — два, резервный канал — три.
            if (alien) AlienSignalMeter(
                level = if (error != null) 1 else if (syncing) 2 else 3,
                modifier = Modifier.padding(end = 10.dp),
            )
            Text(
                error ?: when {
                    syncing && alien -> "синхронизация канала…"
                    syncing -> "Обновление сообщений…"
                    alien -> "резервный канал: обмен каждые 5 секунд"
                    else -> "Медленный режим: обмен каждые 5 секунд (прямое соединение прервано)"
                },
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
    val scope = rememberCoroutineScope()
    // Ошибка быстрого действия свайпом показывается плашкой над списком, а не исчезает в тишине.
    var rowProblem by remember { mutableStateOf<String?>(null) }
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
            rowProblem?.let { problem ->
                NoticeBar(problem, MaterialTheme.colorScheme.error) {
                    TextButton({ rowProblem = null }) { Text("Понятно") }
                }
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
                    ConversationRow(
                        repo = repo,
                        c = c,
                        selected = c.chatId == selectedChatId,
                        onMarkRead = if (c.unreadCount > 0) ({ repo.markChatRead(c.chatId) }) else null,
                        onCall = if (!c.isGroup) ({
                            scope.launch {
                                rowProblem = null
                                try {
                                    repo.startCall(c.chatId, false)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    rowProblem = e.userMessage()
                                }
                            }
                            Unit
                        }) else null,
                        onClick = { onOpenChat(c.chatId) },
                    )
                }
            }
        }
        val alienTokens = com.umbra.app.ui.theme.LocalUmbraAlienTokens.current
        ExtendedFloatingActionButton(
            onClick = { if (filter == "groups") showCreate = true else showNew = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
                .alienGlow(strength = 1.4f)
                .holoEdge(cornerRadius = 20.dp)
                .smokedGlassAction(RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            containerColor = if (alienTokens.enabled) alienTokens.primary.copy(alpha = if (alienTokens.full) 0.92f else 0.86f)
                else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (alienTokens.enabled) Color(0xFF01131A) else MaterialTheme.colorScheme.onPrimaryContainer,
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

/**
 * Строка списка чатов со свайп-действиями: вправо — «Прочитано», влево — звонок.
 *
 * Действия передаются снаружи и могут быть `null`: непрочитанных нет — свайп вправо
 * не тянется, групповой чат — свайп влево недоступен.
 */
@Composable
private fun ConversationRow(
    repo: ChatRepository,
    c: Conversation,
    selected: Boolean,
    onMarkRead: (() -> Unit)? = null,
    onCall: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val visual = LocalUmbraVisuals.current
    val tokens = com.umbra.app.ui.theme.LocalUmbraAlienTokens.current
    val interaction = rememberFutureInteraction()
    val haptics = LocalHapticFeedback.current
    val readTint = if (tokens.enabled) tokens.primary else MaterialTheme.colorScheme.primary
    val callTint = if (tokens.enabled) tokens.secondary else MaterialTheme.colorScheme.tertiary
    SwipeActionRow(
        right = onMarkRead?.let { action ->
            SwipeAction(Icons.Filled.DoneAll, "Прочитано", readTint) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                action()
            }
        },
        left = onCall?.let { action ->
            SwipeAction(Icons.Filled.Call, "Позвонить", callTint) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                action()
            }
        },
    ) { swipe ->
    Row(swipe.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(
            if (selected && tokens.enabled) androidx.compose.ui.graphics.Brush.horizontalGradient(
                listOf(tokens.primary.copy(alpha = 0.20f), tokens.secondary.copy(alpha = 0.10f), Color.Transparent),
            )
            else androidx.compose.ui.graphics.SolidColor(
                if (selected) visual.auraPrimary.copy(alpha = 0.16f) else Color.Transparent,
            ),
        )
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
        if (c.unreadCount > 0) {
            val badgeText = c.unreadCount.coerceAtMost(99).toString()
            if (tokens.enabled) Badge(containerColor = tokens.secondary, contentColor = Color(0xFF0B0216)) { Text(badgeText) }
            else if (LocalUmbraSmokedGlass.current) Badge(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Text(badgeText) }
            else Badge { Text(badgeText) }
        }
    }
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
