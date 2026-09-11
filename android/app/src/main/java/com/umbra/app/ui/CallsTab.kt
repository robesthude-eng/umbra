@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.umbra.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umbra.app.data.InputRules
import com.umbra.app.data.repo.CallUi
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CallsTab(container: AppContainer) {
    val repo = container.chatRepository
    val calls by repo.calls.collectAsState()
    val active by repo.activeCall.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCall by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun reload() {
        loading = true; error = null
        try { repo.fetchCalls() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.userMessage() }
        finally { loading = false }
    }
    fun redial(call: CallUi) {
        if (starting || active != null) return
        starting = true; error = null
        scope.launch {
            try { repo.startCall(call.peerUserId, call.video) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.userMessage() }
            finally { starting = false }
        }
    }
    LaunchedEffect(active) { reload() }
    val sections = remember(calls) { calls.sortedByDescending { it.createdAtMillis }.groupBy { dayKey(it.createdAtMillis) } }
    Column(Modifier.fillMaxSize()) {
        PageHeading("Звонки") {
            FilledTonalIconButton({ showCall = true }, enabled = active == null && !starting) {
                Icon(Icons.Filled.Add, "Новый звонок")
            }
        }
        if (loading || starting) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        error?.let { problem ->
            NoticeBar(problem, MaterialTheme.colorScheme.error) {
                TextButton({ scope.launch { reload() } }, enabled = !loading) { Text("Обновить") }
            }
        }
        if (calls.isEmpty()) {
            AppEmptyState(Icons.Filled.Call, if (loading) "Загружаем звонки" else "Услышать близких",
                if (loading) "История появится здесь." else "Начните голосовой или видеозвонок.", Modifier.weight(1f)) {
                if (!loading) FilledTonalButton({ showCall = true }, enabled = active == null && !starting) { Text("Позвонить") }
            }
        } else LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 24.dp)) {
            sections.forEach { (day, entries) ->
                item(key = "day:$day") {
                    Text(dayLabel(entries.first().createdAtMillis), Modifier.padding(start = 10.dp, top = 18.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(entries, key = { "call:${it.id}" }) { call ->
                    CallHistoryRow(repo, call, enabled = active == null && !starting) { redial(call) }
                }
            }
        }
    }
    if (showCall) CallPickerSheet(repo, { showCall = false }) { id, video ->
        repo.startCall(id, video)
        showCall = false
    }
}

@Composable
private fun CallHistoryRow(repo: ChatRepository, call: CallUi, enabled: Boolean, onCall: () -> Unit) {
    val missed = call.status == "missed" && call.incoming
    val detailColor = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
        .clickable(enabled = enabled, onClickLabel = if (call.video) "Повторить видеозвонок" else "Перезвонить", onClick = onCall)
        .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        UserAvatar(repo, call.peerUserId, call.peerName, 52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(call.peerName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(clockText(call.createdAtMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(if (missed) Icons.Filled.CallMissed else if (call.incoming) Icons.Filled.CallReceived else Icons.Filled.CallMade,
                    null, Modifier.size(16.dp), tint = detailColor)
                Text(
                    if (missed) "Пропущенный" else (if (call.incoming) "Входящий" else "Исходящий") + " · " + callStatusText(call.status),
                    style = MaterialTheme.typography.bodySmall, color = detailColor, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(if (call.video) Icons.Filled.Videocam else Icons.Filled.Call, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

internal fun callStatusText(status: String) = when (status) {
    "ringing" -> "Вызов"
    "active" -> "Принят"
    "ended" -> "Завершён"
    "declined" -> "Отклонён"
    "missed" -> "Без ответа"
    else -> "Статус неизвестен"
}

@Composable
private fun CallPickerSheet(repo: ChatRepository, onDismiss: () -> Unit, onCall: suspend (String, Boolean) -> Unit) {
    val flow = remember(repo) { repo.conversations() }
    val conversations by flow.collectAsState(emptyList())
    var username by rememberSaveable { mutableStateOf("") }
    var video by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun start(id: String?) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                val peer = id ?: (repo.resolveByUsername(InputRules.username(username))
                    ?: throw IllegalArgumentException("Пользователь не найден. Проверьте никнейм.")).id
                onCall(peer, video)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.userMessage() }
            finally { busy = false }
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy || it != SheetValue.Hidden })
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Кому позвонить?", style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                .toggleable(video, enabled = !busy, role = Role.Switch) { video = it }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("С видео", Modifier.weight(1f))
                Switch(video, onCheckedChange = null, enabled = !busy)
            }
            OutlinedTextField(username, { username = it.take(33); error = null }, Modifier.fillMaxWidth(),
                label = { Text("@никнейм") }, singleLine = true, enabled = !busy)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button({ start(null) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !busy && InputRules.validUsername(InputRules.username(username))) {
                Text(if (busy) "Соединяем…" else if (video) "Позвонить с видео" else "Позвонить")
            }
            val people = conversations.filter { !it.isGroup && it.chatId != repo.me() }
            if (people.isNotEmpty()) Text("Ваши собеседники", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            people.forEach { person ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = !busy) { start(person.chatId) }
                    .padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    UserAvatar(repo, person.chatId, person.title, 44.dp)
                    Text(person.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Icon(if (video) Icons.Filled.Videocam else Icons.Filled.Call, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
