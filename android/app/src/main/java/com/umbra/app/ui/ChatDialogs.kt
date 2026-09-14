@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.umbra.app.ui

import com.umbra.app.data.media.MediaSendQuality
import com.umbra.app.data.msg.Reactions
import kotlinx.coroutines.delay

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.umbra.app.data.InputRules
import com.umbra.app.data.api.UserCard
import com.umbra.app.data.media.Attachments
import com.umbra.app.data.msg.voiceDurationText
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.data.repo.UiAttachment
import com.umbra.app.data.repo.UiMessage
import com.umbra.app.data.repo.VoiceMessage
import com.umbra.app.data.voice.VoicePlayback
import com.umbra.app.data.voice.VoiceRecordingState
import com.umbra.app.di.AppContainer
import com.umbra.app.ui.theme.LocalUmbraChatColors
import com.umbra.app.ui.theme.LocalUmbraSmokedGlass
import com.umbra.app.ui.theme.LocalUmbraReducedMotion
import com.umbra.app.ui.theme.LocalUmbraMessageTextStyle
import com.umbra.app.ui.theme.UmbraChatColors
import com.umbra.app.ui.theme.rememberUmbraChatColors
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/*
 * Диалоги экрана чата: пересылка, участники группы, групповой звонок
 * и кнопка «к последним сообщениям». Вынесено из ChatView.kt.
 */

@Composable
internal fun ForwardMessageDialog(
    repo: ChatRepository,
    currentChatId: String,
    message: UiMessage,
    onDismiss: () -> Unit,
    onForward: (String) -> Unit,
) {
    val conversations by remember(repo) { repo.conversations() }.collectAsState(emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переслать сообщение") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(conversations.filter { it.chatId != currentChatId }, key = { it.chatId }) { chat ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .clickable { onForward(chat.chatId) }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (chat.isGroup) GroupAvatar(chat.title, 40.dp)
                            else UserAvatar(repo, chat.chatId, chat.title, 40.dp)
                            Text(chat.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}

@Composable
internal fun GroupMembersDialog(repo: ChatRepository, chatId: String, onDismiss: () -> Unit) {
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

/**
 * Кого зовём в групповой звонок. Соединения идут напрямую между телефонами,
 * поэтому выбрать можно не больше трёх собеседников.
 */
@Composable
internal fun GroupCallDialog(
    repo: ChatRepository,
    chatId: String,
    video: Boolean,
    onDismiss: () -> Unit,
    onStart: (List<String>) -> Unit,
) {
    var members by remember(chatId) { mutableStateOf<List<UserCard>>(emptyList()) }
    var selected by remember(chatId) { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember(chatId) { mutableStateOf(false) }
    var error by remember(chatId) { mutableStateOf<String?>(null) }
    LaunchedEffect(chatId) {
        loading = true; error = null
        try {
            val me = repo.me()
            val others = repo.groupMembers(chatId).filter { it.id.isNotBlank() && it.id != me }
            members = others
            // В маленькой группе выбирать нечего: сразу отмечаем всех, кто влезает.
            selected = others.take(MAX_GROUP_CALL_PEERS).map { it.id }.toSet()
        } catch (e: Exception) { error = e.userMessage() }
        finally { loading = false }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (video) "Групповой видеозвонок" else "Групповой звонок") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    "Отметьте до $MAX_GROUP_CALL_PEERS собеседников: звонок идёт напрямую между телефонами.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (member in members) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = member.id in selected,
                        onCheckedChange = { wanted ->
                            selected = when {
                                !wanted -> selected - member.id
                                selected.size >= MAX_GROUP_CALL_PEERS -> selected
                                else -> selected + member.id
                            }
                        },
                    )
                    Avatar(repo, member.avatarMediaId, member.fullName(), 36.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(member.fullName())
                }
                if (!loading && error == null && members.isEmpty()) Text("В группе больше никого нет")
            }
        },
        confirmButton = {
            TextButton({ onStart(selected.toList()) }, enabled = selected.isNotEmpty() && !loading) { Text("Позвонить") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}

/**
 * Кнопка «к последним сообщениям»: появляется, когда список уехал вверх.
 *
 * Вынесена из [ChatScreen], потому что вызов AnimatedVisibility прямо внутри
 * Box, вложенного в Column, не проходит разрешение перегрузок в K2 (компилятор
 * останавливается на расширении ColumnScope с внешнего приёмника). В отдельной
 * функции scope-приёмников нет, и применяется top-level AnimatedVisibility.
 */
@Composable
internal fun ScrollToBottomPill(
    visible: Boolean,
    modifier: Modifier = Modifier,
    palette: UmbraChatColors,
    reducedMotion: Boolean,
    onScrollToEnd: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reducedMotion) EnterTransition.None else fadeIn() + scaleIn(),
        exit = if (reducedMotion) ExitTransition.None else fadeOut() + scaleOut(),
    ) {
        Box(
            Modifier.size(46.dp).shadow(12.dp, CircleShape).clip(CircleShape)
                .background(palette.bar)
                .alienGlow(strength = 1.1f)
                .clickable(onClick = onScrollToEnd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, "К последним сообщениям", tint = palette.accent)
        }
    }
}
