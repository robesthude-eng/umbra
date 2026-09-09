@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.umbra.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umbra.app.data.InputRules
import com.umbra.app.data.api.UserCard
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.data.repo.UiMessage
import com.umbra.app.di.AppContainer
import com.umbra.app.ui.theme.UmbraColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ChatView(container: AppContainer, chatId: String, onBack: () -> Unit) {
    val repo = container.chatRepository
    val chatFlow = remember(chatId) { container.database.chatDao().observe(chatId) }
    val chat by chatFlow.collectAsState(null)
    val messageFlow = remember(chatId) { repo.messagesFor(chatId) }
    val messages by messageFlow.collectAsState(emptyList())
    val users by repo.userCache.collectAsState()
    var input by rememberSaveable(chatId) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showMembers by rememberSaveable(chatId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val isGroup = chat?.type?.let { it != "dm" } ?: false
    val available = chat != null && chat?.type != "unavailable"
    val title = chat?.title?.ifBlank { "Чат" } ?: "Загрузка…"
    val nearBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    val latestId = messages.lastOrNull()?.stableId
    // Reverse layout starts at the newest message and preserves position while reading older messages.
    LaunchedEffect(latestId) {
        if (latestId != null && nearBottom) listState.animateScrollToItem(0)
    }
    BackHandler(onBack = onBack)
    Scaffold(containerColor = Color.Transparent, modifier = Modifier.imePadding(), topBar = {
        TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isGroup) Avatar(repo, null, title, 36.dp) else UserAvatar(repo, chatId, title, 36.dp)
                Spacer(Modifier.width(10.dp))
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } }, actions = {
            if (isGroup && available) IconButton({ showMembers = true }) { Icon(Icons.Filled.Groups, "Участники группы") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            SyncBanner(repo)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) Text("Здесь появятся сообщения", Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(messages.asReversed(), key = { it.stableId }) { message ->
                        MessageBubble(message, if (isGroup && !message.outgoing) users[message.senderId]?.fullName() ?: "Участник" else null) {
                            scope.launch {
                                try { repo.retryMessage(message.id) }
                                catch (e: Exception) { error = e.userMessage() }
                            }
                        }
                    }
                }
                if (!nearBottom && messages.isNotEmpty()) SmallFloatingActionButton(
                    { scope.launch { listState.animateScrollToItem(0) } }, Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) { Icon(Icons.Filled.KeyboardArrowDown, "К последним сообщениям") }
            }
            error?.let { problem ->
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(problem, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton({ error = null }) { Text("Понятно") }
                }
            }
            if (chat?.type == "unavailable") Text("Доступ к группе прекращён. Сохранённую историю можно прочитать.", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    input, { input = it; error = null }, Modifier.weight(1f), enabled = !sending && available,
                    placeholder = { Text("Сообщение…") }, maxLines = 5,
                    isError = input.length > InputRules.MAX_TEXT_LENGTH,
                    supportingText = if (input.length > InputRules.MAX_TEXT_LENGTH) ({ Text("Максимум 16 000 символов") }) else null,
                )
                Spacer(Modifier.width(6.dp))
                FilledIconButton(enabled = !sending && available && input.isNotBlank() && input.length <= InputRules.MAX_TEXT_LENGTH, onClick = {
                    if (!sending) {
                        val text = input
                        sending = true; error = null
                        scope.launch {
                            try {
                                repo.sendText(chatId, text)
                                input = ""
                                listState.animateScrollToItem(0)
                            } catch (e: Exception) { error = e.userMessage() }
                            finally { sending = false }
                        }
                    }
                }) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить") }
            }
        }
    }
    if (showMembers) GroupMembersDialog(repo, chatId) { showMembers = false }
}

@Composable
private fun MessageBubble(message: UiMessage, senderName: String?, onRetry: () -> Unit) {
    val textColor = if (message.outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
        senderName?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        Surface(shape = RoundedCornerShape(18.dp), color = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                SelectionContainer { Text(message.text, color = textColor) }
                val status = when {
                    message.failed -> "Не отправлено"
                    message.pending -> "Ожидает отправки"
                    message.outgoing -> "Отправлено"
                    else -> ""
                }
                Text(listOf(timeText(message.createdAtMillis), status).filter { it.isNotBlank() }.joinToString(" · "),
                    Modifier.fillMaxWidth().padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = textColor, textAlign = TextAlign.End)
                if (message.failed) {
                    message.error?.let { Text(it, color = textColor, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onRetry) { Text("Повторить отправку", color = textColor) }
                }
            }
        }
    }
}

@Composable
private fun GroupMembersDialog(repo: ChatRepository, chatId: String, onDismiss: () -> Unit) {
    var members by remember { mutableStateOf<List<UserCard>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    suspend fun reload() {
        loading = true; error = null
        try { members = repo.groupMembers(chatId) }
        catch (e: Exception) { error = e.userMessage() }
        finally { loading = false }
    }
    LaunchedEffect(chatId, revision) { reload() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Участники группы") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton({ scope.launch { reload() } }, enabled = !loading) { Text("Повторить") } }
            for (member in members) Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(repo, member.avatarMediaId, member.fullName(), 36.dp)
                Spacer(Modifier.width(8.dp))
                Column { Text(member.fullName()); Text("@${member.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Закрыть") } }, dismissButton = { TextButton({ showAdd = true }, enabled = !loading) { Text("Добавить") } })
    if (showAdd) UsernameDialog("Добавить участника", "Приглашать участников может владелец или администратор группы.", "Добавить", { showAdd = false }) { name ->
        val user = repo.resolveByUsername(name) ?: throw IllegalArgumentException("Пользователь не найден")
        repo.addGroupMembers(chatId, listOf(user.id))
        showAdd = false; revision++
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())
private fun timeText(millis: Long) = if (millis <= 0) "" else timeFormat.format(Instant.ofEpochMilli(millis))
