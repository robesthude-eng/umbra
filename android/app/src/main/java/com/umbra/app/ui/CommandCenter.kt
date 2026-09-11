@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.umbra.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.umbra.app.data.repo.ChatRepository

/** Быстрая навигация по Umbra. Tasks-команды появятся после реализации модуля задач. */
@Composable
internal fun CommandCenter(
    repo: ChatRepository,
    onDismiss: () -> Unit,
    onOpenChat: (String) -> Unit,
    onDestination: (Int) -> Unit,
) {
    val conversations by remember(repo) { repo.conversations() }.collectAsState(emptyList())
    var query by remember { mutableStateOf("") }
    val needle = query.trim()
    val visible = remember(conversations, needle) {
        if (needle.isEmpty()) conversations.take(8) else conversations.filter {
            it.title.contains(needle, true) || it.subtitle.contains(needle, true)
        }.take(20)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 44.dp), contentAlignment = Alignment.TopCenter) {
            GlassPanel(Modifier.fillMaxWidth().widthIn(max = 620.dp), strong = true) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Command Center", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                        AssistChip(onClick = {}, enabled = false, label = { Text("Ctrl/⌘ K") })
                        IconButton(onDismiss) { Icon(Icons.Filled.Close, "Закрыть") }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(160) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Чат или раздел") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CommandDestination(Icons.Filled.Chat, "Чаты", Modifier.weight(1f)) { onDestination(0); onDismiss() }
                        CommandDestination(Icons.Filled.Call, "Звонки", Modifier.weight(1f)) { onDestination(2); onDismiss() }
                        CommandDestination(Icons.Filled.Settings, "Настройки", Modifier.weight(1f)) { onDestination(3); onDismiss() }
                    }
                    Text(
                        if (needle.isEmpty()) "Недавние чаты" else "Результаты",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (visible.isEmpty()) {
                        Text("Ничего не найдено", Modifier.padding(vertical = 20.dp).align(Alignment.CenterHorizontally))
                    } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(visible, key = { it.chatId }) { conversation ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    onOpenChat(conversation.chatId)
                                    onDismiss()
                                }.padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (conversation.isGroup) GroupAvatar(conversation.title, 42.dp)
                                else UserAvatar(repo, conversation.chatId, conversation.title, 42.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                    Text(conversation.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (conversation.unreadCount > 0) Badge { Text(conversation.unreadCount.coerceAtMost(99).toString()) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandDestination(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick, modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}
