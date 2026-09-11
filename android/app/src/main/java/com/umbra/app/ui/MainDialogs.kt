@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.umbra.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.umbra.app.data.InputRules
import com.umbra.app.data.repo.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CreateGroupDialog(repo: ChatRepository, onDismiss: () -> Unit, onOpenChat: (String) -> Unit) {
    val flow = remember(repo) { repo.conversations() }
    val conversations by flow.collectAsState(emptyList())
    var title by rememberSaveable { mutableStateOf("") }
    var members by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var createdId by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy || it != SheetValue.Hidden })
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (createdId == null) "Новая группа" else "Приглашение участников", style = MaterialTheme.typography.headlineSmall)
            if (createdId != null) Text("Группа уже создана. Повторите приглашение или откройте её.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(title, { title = it.take(100); error = null }, Modifier.fillMaxWidth(),
                enabled = !busy && createdId == null, label = { Text("Название группы") }, singleLine = true)
            val people = conversations.filter { !it.isGroup && it.chatId != repo.me() }
            if (people.isNotEmpty()) {
                Text(if (selected.isEmpty()) "Выберите участников" else "Выбрано: ${selected.size}",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                people.forEach { person ->
                    val checked = person.chatId in selected
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .toggleable(checked, enabled = !busy, role = Role.Checkbox) { value ->
                            selected = if (value) selected + person.chatId else selected - person.chatId
                            error = null
                        }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        UserAvatar(repo, person.chatId, person.title, 44.dp)
                        Text(person.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Checkbox(checked, onCheckedChange = null, enabled = !busy)
                    }
                }
            }
            OutlinedTextField(members, { members = it.take(1000); error = null }, Modifier.fillMaxWidth(),
                enabled = !busy, label = { Text("Другие участники") }, maxLines = 4,
                supportingText = { Text("Необязательно: @никнеймы через запятую") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(enabled = !busy && title.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val names = members.split(',', '\n').map(InputRules::username).filter { it.isNotBlank() }.distinct()
                        val ids = (selected + names.map { name ->
                            (repo.resolveByUsername(name) ?: throw IllegalArgumentException("@$name не найден. Проверьте никнейм.")).id
                        }).filter { it != repo.me() }.distinct()
                        val id = createdId ?: repo.createGroup(title).also { createdId = it }
                        repo.addGroupMembers(id, ids)
                        onOpenChat(id)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.userMessage() }
                    finally { busy = false }
                }
            }) { Text(if (createdId == null) "Создать группу" else "Повторить приглашение") }
            TextButton(onClick = { createdId?.let(onOpenChat) ?: onDismiss() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (createdId == null) "Отмена" else "Открыть группу")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
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

