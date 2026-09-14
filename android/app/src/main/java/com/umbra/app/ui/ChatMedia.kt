package com.umbra.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umbra.app.data.media.Attachments
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.data.repo.UiMessage

/**
 * Галерея чата: всё, что было отправлено и получено вложениями,
 * без дополнительных запросов: данные берём из уже загруженного списка.
 * Первыми идут свежие файлы.
 */
@Composable
internal fun ChatMediaDialog(
    repo: ChatRepository,
    messages: List<UiMessage>,
    onOpen: (UiMessage) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    val palette = LocalUmbraChatColors.current
    val media = remember(messages) {
        messages.filter { it.attachment != null && !it.deleted }.reversed()
    }
    val visual = remember(media) { media.filter { it.attachment?.let { a -> a.isImage || a.isVideo } == true } }
    val files = remember(media) { media.filter { it.attachment?.let { a -> !a.isImage && !a.isVideo } == true } }
    val shown = if (tab == 0) visual else files
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Медиа и файлы") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Фото и видео (${visual.size})") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Файлы (${files.size})") })
                }
                Spacer(Modifier.height(12.dp))
                if (shown.isEmpty()) Text(
                    "Здесь появятся вложения из этого чата.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else if (tab == 0) LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(shown, key = { it.stableId }) { message ->
                        val attachment = message.attachment ?: return@items
                        AttachmentPhoto(
                            repo = repo,
                            attachment = attachment,
                            palette = palette,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpen(message) },
                            width = 96.dp,
                        )
                    }
                }
                else Column(Modifier.weight(1f, fill = false)) {
                    shown.take(60).forEach { message ->
                        val attachment = message.attachment ?: return@forEach
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(message) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.InsertDriveFile, null, tint = palette.accent)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    Attachments.sizeText(attachment.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Закрыть") } },
    )
}
