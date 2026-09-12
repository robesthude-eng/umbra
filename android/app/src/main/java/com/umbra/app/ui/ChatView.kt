@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.umbra.app.ui

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

/** Сообщения одного автора с разницей меньше этого склеиваются в группу. */
private const val GROUP_WINDOW_MS = 5 * 60 * 1000L

/** Потолок звонка без серверного микшера: четверо участников, то есть трое приглашённых. */
private const val MAX_GROUP_CALL_PEERS = 3

/** Элемент ленты: разделитель дня либо сообщение с пометками группировки. */
private sealed interface ChatItem {
    data class Day(val key: String, val label: String) : ChatItem
    data class Bubble(
        val message: UiMessage,
        val first: Boolean,
        val last: Boolean,
        val showName: Boolean,
    ) : ChatItem
}

private fun buildChatItems(messages: List<UiMessage>, isGroup: Boolean): List<ChatItem> {
    val items = ArrayList<ChatItem>(messages.size + 4)
    var previousDay: String? = null
    messages.forEachIndexed { index, message ->
        val day = dayKey(message.createdAtMillis)
        if (day != previousDay) {
            items.add(ChatItem.Day(day, dayLabel(message.createdAtMillis)))
            previousDay = day
        }
        val previous = messages.getOrNull(index - 1)
        val next = messages.getOrNull(index + 1)
        val first = previous == null || previous.senderId != message.senderId ||
            previous.outgoing != message.outgoing ||
            dayKey(previous.createdAtMillis) != day ||
            message.createdAtMillis - previous.createdAtMillis > GROUP_WINDOW_MS
        val last = next == null || next.senderId != message.senderId ||
            next.outgoing != message.outgoing ||
            dayKey(next.createdAtMillis) != day ||
            next.createdAtMillis - message.createdAtMillis > GROUP_WINDOW_MS
        items.add(ChatItem.Bubble(message, first, last, isGroup && !message.outgoing && first))
    }
    return items
}

@Composable
fun ChatView(container: AppContainer, chatId: String, onBack: () -> Unit) {
    val palette = rememberUmbraChatColors(chatId)
    CompositionLocalProvider(LocalUmbraChatColors provides palette) {
        ChatViewContent(container, chatId, onBack)
    }
}

@Composable
private fun ChatViewContent(container: AppContainer, chatId: String, onBack: () -> Unit) {
    val repo = container.chatRepository
    val recorder = container.voiceRecorder
    val player = container.voicePlayer
    val context = LocalContext.current
    val chatFlow = remember(chatId) { container.database.chatDao().observe(chatId) }
    val chat by chatFlow.collectAsState(null)
    val messageFlow = remember(chatId) { repo.messagesFor(chatId) }
    val messages by messageFlow.collectAsState(emptyList())
    val users by repo.userCache.collectAsState()
    val recording by recorder.state.collectAsState()
    val playback by player.state.collectAsState()
    val connected by repo.connected.collectAsState()
    val syncing by repo.syncing.collectAsState()
    val syncError by repo.syncError.collectAsState()
    var input by rememberSaveable(chatId) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showMembers by rememberSaveable(chatId) { mutableStateOf(false) }
    var showGroupCall by rememberSaveable(chatId) { mutableStateOf(false) }
    var groupCallVideo by rememberSaveable(chatId) { mutableStateOf(false) }
    var replyingTo by remember(chatId) { mutableStateOf<UiMessage?>(null) }
    var forwarding by remember(chatId) { mutableStateOf<UiMessage?>(null) }
    var editing by remember(chatId) { mutableStateOf<UiMessage?>(null) }
    var deleting by remember(chatId) { mutableStateOf<UiMessage?>(null) }
    var searching by rememberSaveable(chatId) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(chatId) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val palette = LocalUmbraChatColors.current
    val isGroup = chat?.type?.let { it != "dm" } ?: false
    val available = chat != null && chat?.type != "unavailable"
    val title = (if (isGroup) chat?.title else users[chatId]?.fullName() ?: chat?.title)?.ifBlank { "Чат" } ?: "Загрузка…"
    val nearBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    val latestId = messages.lastOrNull()?.stableId
    val visibleMessages = remember(messages, searchQuery) {
        if (searchQuery.isBlank()) messages else messages.filter { it.text.contains(searchQuery.trim(), ignoreCase = true) }
    }
    val rendered = remember(visibleMessages, isGroup) { buildChatItems(visibleMessages, isGroup) }
    val reducedMotion = LocalUmbraReducedMotion.current
    suspend fun goToLatest() {
        if (reducedMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0)
    }
    // Reverse layout starts at the newest message and preserves position while reading older messages.
    LaunchedEffect(latestId) {
        if (latestId != null && nearBottom) goToLatest()
        repo.markChatRead(chatId, messages.maxOfOrNull { it.createdAtMillis } ?: 0L)
    }
    // Ушли с экрана — глушим звук и выкидываем недозаписанную запись.
    DisposableEffect(chatId) {
        onDispose {
            player.stopAsync()
            recorder.cancelAsync()
        }
    }

    fun beginRecording() {
        error = null
        scope.launch {
            try { recorder.start() } catch (e: Exception) { error = e.userMessage() }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecording()
        else error = "Разрешите доступ к микрофону, чтобы записывать голосовые сообщения."
    }
    fun requestRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecording()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun sendRecording() {
        if (sending) return
        sending = true; error = null
        scope.launch {
            try {
                val recorded = recorder.finish()
                repo.sendVoice(chatId, recorded)
                goToLatest()
            } catch (e: Exception) {
                error = e.userMessage()
                runCatching { recorder.cancel() }
            } finally { sending = false }
        }
    }
    fun cancelRecording() {
        error = null
        scope.launch { runCatching { recorder.cancel() } }
    }
    var showAttachMenu by remember { mutableStateOf(false) }
    fun sendPicked(uris: List<Uri>) {
        if (uris.isEmpty() || sending) return
        sending = true; error = null
        val caption = input.trim()
        val reply = replyingTo
        scope.launch {
            try {
                uris.take(10).forEachIndexed { index, uri ->
                    repo.sendAttachment(
                        chatId,
                        uri,
                        caption = if (index == 0) caption else "",
                        replyTo = if (index == 0) reply else null,
                    )
                }
                if (caption.isNotEmpty()) input = ""
                replyingTo = null
                goToLatest()
            } catch (e: Exception) { error = e.userMessage() }
            finally { sending = false }
        }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> sendPicked(uris) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> sendPicked(listOfNotNull(uri)) }
    // Запись видеосообщения прямо из чата: камера пишет в приватный каталог,
    // оттуда запись уходит обычным вложением, а временный файл удаляется.
    var captureTarget by remember { mutableStateOf<File?>(null) }
    fun sendCaptured(file: File) {
        if (sending) return
        sending = true; error = null
        scope.launch {
            try {
                repo.sendAttachment(chatId, Attachments.uriFor(context, file), input.trim(), replyingTo)
                if (input.isNotBlank()) input = ""
                replyingTo = null
                goToLatest()
            } catch (e: Exception) { error = e.userMessage() }
            finally {
                sending = false
                runCatching { file.delete() }
            }
        }
    }
    val captureVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { saved ->
        val file = captureTarget
        captureTarget = null
        if (file != null) {
            if (saved) sendCaptured(file) else runCatching { file.delete() }
        }
    }
    fun beginVideoCapture() {
        if (sending) return
        error = null
        val file = repo.videoCaptureFile()
        val uri = runCatching { Attachments.uriFor(context, file) }.getOrNull()
        if (uri == null) {
            error = "Не удалось подготовить запись видео."
            return
        }
        captureTarget = file
        runCatching { captureVideo.launch(uri) }.onFailure {
            captureTarget = null
            runCatching { file.delete() }
            error = "На устройстве нет приложения камеры для записи видео."
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginVideoCapture()
        else error = "Разрешите доступ к камере, чтобы записывать видеосообщения."
    }
    fun requestVideoCapture() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) beginVideoCapture()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    // Сохранение через системный выбор папки: разрешения на галерею не нужны.
    var pendingSave by remember { mutableStateOf<UiAttachment?>(null) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
        val attachment = pendingSave
        pendingSave = null
        if (target != null && attachment != null) scope.launch {
            error = null
            try { repo.saveAttachmentTo(attachment, target) }
            catch (e: Exception) { error = e.userMessage() }
        }
    }
    // Фото и видео открываются внутри Umbra (см. MediaViewer.kt).
    var viewing by remember(chatId) { mutableStateOf<UiAttachment?>(null) }
    var viewingOrigin by remember(chatId) { mutableStateOf<Rect?>(null) }
    var viewerRootBounds by remember(chatId) { mutableStateOf<Rect?>(null) }
    /** Внешнее приложение осталось запасным путём для любых форматов. */
    fun openExternally(attachment: UiAttachment) {
        error = null
        scope.launch {
            try {
                val file = repo.attachmentLocalFile(attachment)
                context.startActivity(Attachments.openIntent(context, file, attachment.mime))
            } catch (e: Exception) { error = e.userMessage() }
        }
    }
    fun openAttachment(attachment: UiAttachment, origin: Rect? = null) {
        error = null
        if (attachment.isImage || attachment.isVideo) {
            viewingOrigin = origin
            viewing = attachment
        } else openExternally(attachment)
    }
    fun shareAttachment(attachment: UiAttachment) {
        error = null
        scope.launch {
            try {
                val file = repo.attachmentLocalFile(attachment)
                context.startActivity(Attachments.shareIntent(context, file, attachment.mime))
            } catch (e: Exception) { error = e.userMessage() }
        }
    }
    fun saveAttachment(attachment: UiAttachment) {
        pendingSave = attachment
        saveFile.launch(attachment.name)
    }
    fun startCall(video: Boolean) {
        error = null
        scope.launch {
            // В личном чате chatId совпадает с id собеседника.
            try { repo.startCall(chatId, video) }
            catch (e: Exception) { error = e.userMessage() }
        }
    }
    /** Звонок выбранным участникам группы: соединения идут напрямую между телефонами. */
    fun startGroupCall(invited: List<String>, video: Boolean) {
        error = null
        scope.launch {
            try { repo.startCall(invited, video) }
            catch (e: Exception) { error = e.userMessage() }
        }
    }
    fun sendText() {
        if (sending || input.isBlank()) return
        val text = input
        sending = true; error = null
        scope.launch {
            try {
                repo.sendText(chatId, text, replyingTo)
                input = ""
                replyingTo = null
                goToLatest()
            } catch (e: Exception) { error = e.userMessage() }
            finally { sending = false }
        }
    }

    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().onGloballyPositioned { viewerRootBounds = it.boundsInWindow() }) {
        ChatBackground(Modifier.matchParentSize())
        Scaffold(
            containerColor = Color.Transparent,
            modifier = Modifier.imePadding(),
            topBar = {
                ChatTopBar(
                    repo = repo,
                    chatId = chatId,
                    title = title,
                    isGroup = isGroup,
                    available = available,
                    connected = connected,
                    syncing = syncing,
                    syncError = syncError,
                    onBack = onBack,
                    onRefresh = { scope.launch { runCatching { repo.refresh(forceFull = true) } } },
                    onCall = { video ->
                        if (isGroup) { groupCallVideo = video; showGroupCall = true } else startCall(video)
                    },
                    onMembers = { showMembers = true },
                    onSearch = { searching = !searching; if (!searching) searchQuery = "" },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (searching) OutlinedTextField(
                    searchQuery, { searchQuery = it.take(200) },
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    placeholder = { Text("Поиск в переписке") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true,
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (rendered.isEmpty()) {
                        if (searchQuery.isNotBlank()) AppEmptyState(
                            icon = Icons.Filled.Search,
                            title = "Ничего не найдено",
                            description = "Попробуйте изменить запрос.",
                            modifier = Modifier.align(Alignment.Center),
                        ) else ChatEmptyState(Modifier.align(Alignment.Center))
                    }
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        items(
                            items = rendered.asReversed(),
                            key = { item ->
                                when (item) {
                                    is ChatItem.Day -> "day:" + item.key
                                    is ChatItem.Bubble -> item.message.stableId
                                }
                            },
                            contentType = { item -> if (item is ChatItem.Day) "day" else "message" },
                        ) { item ->
                            when (item) {
                                is ChatItem.Day -> DateChip(item.label, if (reducedMotion) Modifier else Modifier.animateItem())
                                is ChatItem.Bubble -> MessageRow(
                                    repo = repo,
                                    item = item,
                                    isGroup = isGroup,
                                    senderName = if (isGroup && !item.message.outgoing)
                                        users[item.message.senderId]?.fullName() ?: "Участник" else null,
                                    playback = playback?.takeIf { it.key == item.message.stableId },
                                    modifier = if (reducedMotion) Modifier else Modifier.animateItem(),
                                    onOpen = { attachment, bounds -> openAttachment(attachment, bounds) },
                                    onShare = { shareAttachment(it) },
                                    onSave = { saveAttachment(it) },
                                    onTogglePlay = {
                                        val voice = item.message.voice
                                        if (voice != null) scope.launch {
                                            try { player.toggle(item.message.stableId, voice.mediaId, voice.localPath, voice.durationMs) }
                                            catch (e: Exception) { error = e.userMessage() }
                                        }
                                    },
                                    onSeek = { positionMs ->
                                        scope.launch {
                                            try { player.seekTo(item.message.stableId, positionMs) }
                                            catch (e: Exception) { error = e.userMessage() }
                                        }
                                    },
                                    onSpeed = {
                                        scope.launch {
                                            try { player.setSpeed(nextVoiceSpeed(playback?.speed ?: 1f)) }
                                            catch (e: Exception) { error = e.userMessage() }
                                        }
                                    },
                                    onRetry = {
                                        scope.launch {
                                            try { repo.retryMessage(item.message.id) }
                                            catch (e: Exception) { error = e.userMessage() }
                                        }
                                    },
                                    onReply = { replyingTo = item.message },
                                    onForward = { forwarding = item.message },
                                    onEdit = { editing = item.message },
                                    onDelete = { deleting = item.message },
                                    onReact = { emoji -> scope.launch {
                                        runCatching { repo.reactToMessage(chatId, item.message, emoji) }
                                            .onFailure { error = it.userMessage() }
                                    } },
                                )
                            }
                        }
                    }
                    // K2 не даёт вызывать AnimatedVisibility прямо здесь (внутри
                    // Box, вложенного в Column: компилятор выбирает расширение
                    // ColumnScope и отказывает «implicit receiver»), поэтому
                    // кнопка вынесена в отдельную композабел-функцию — в ней
                    // применяется top-level AnimatedVisibility (Compose 1.7).
                    ScrollToBottomPill(
                        visible = searchQuery.isBlank() && !nearBottom && messages.isNotEmpty(),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        palette = palette,
                        reducedMotion = reducedMotion,
                        onScrollToEnd = { scope.launch { goToLatest() } },
                    )
                }
                val problem = error
                AnimatedVisibility(
                    visible = problem != null,
                    enter = if (reducedMotion) EnterTransition.None else fadeIn() + expandVertically(),
                    exit = if (reducedMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
                ) {
                    NoticeBar(problem ?: "", MaterialTheme.colorScheme.error) {
                        TextButton({ error = null }) { Text("Понятно") }
                    }
                }
                if (chat?.type == "unavailable") NoticeBar(
                    "Доступ к группе прекращён. Сохранённую историю можно прочитать.",
                    palette.incomingMeta,
                )
                val activeRecording = recording
                if (activeRecording != null) {
                    VoiceRecordingBar(activeRecording, sending, onCancel = { cancelRecording() }, onSend = { sendRecording() })
                } else {
                    ChatComposer(
                        input = input,
                        onInput = { input = it; error = null },
                        enabled = !sending && available,
                        tooLong = input.length > InputRules.MAX_TEXT_LENGTH,
                        onAttach = { showAttachMenu = true },
                        onSend = { sendText() },
                        onMic = { requestRecording() },
                        replyText = replyingTo?.text,
                        onCancelReply = { replyingTo = null },
                    )
                }
            }
        }
        val islandLabel = when {
            sending -> "Отправляем…"
            recording != null -> "Идёт запись"
            else -> null
        }
        AnimatedVisibility(
            visible = islandLabel != null,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 54.dp),
            enter = if (reducedMotion) EnterTransition.None else fadeIn() + scaleIn(initialScale = 0.92f),
            exit = if (reducedMotion) ExitTransition.None else fadeOut() + scaleOut(targetScale = 0.92f),
        ) {
            ActivityIsland(
                icon = if (recording != null) Icons.Filled.Mic else Icons.AutoMirrored.Filled.Send,
                label = islandLabel.orEmpty(),
            )
        }
        val viewed = viewing
        if (viewed != null) {
            val origin = viewingOrigin
            val root = viewerRootBounds
            val sourceInRoot = if (origin != null && root != null) Rect(
                origin.left - root.left, origin.top - root.top,
                origin.right - root.left, origin.bottom - root.top,
            ) else null
            AttachmentViewerOverlay(
                repo = repo,
                attachment = viewed,
                sourceBounds = sourceInRoot,
                onDismiss = { viewing = null; viewingOrigin = null },
                onOpenExternally = {
                    viewing = null; viewingOrigin = null
                    openExternally(viewed)
                },
                onShare = { shareAttachment(viewed) },
                onSave = { saveAttachment(viewed) },
            )
        }
    }
    if (showAttachMenu) AttachSheet(
        onDismiss = { showAttachMenu = false },
        onPickMedia = {
            showAttachMenu = false
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        },
        onRecordVideo = {
            showAttachMenu = false
            requestVideoCapture()
        },
        onPickFile = {
            showAttachMenu = false
            pickFile.launch(arrayOf("*/*"))
        },
    )
    if (showMembers) GroupMembersDialog(repo, chatId) { showMembers = false }
    if (showGroupCall) GroupCallDialog(repo, chatId, groupCallVideo, { showGroupCall = false }) { invited ->
        showGroupCall = false
        startGroupCall(invited, groupCallVideo)
    }
    forwarding?.let { message ->
        ForwardMessageDialog(repo, chatId, message, onDismiss = { forwarding = null }) { target ->
            forwarding = null
            scope.launch {
                try { repo.forwardMessage(target, message) }
                catch (e: Exception) { error = e.userMessage() }
            }
        }
    }
    editing?.let { message ->
        var editedText by remember(message.id) { mutableStateOf(message.text) }
        AlertDialog(
            onDismissRequest = { editing = null }, title = { Text("Редактировать сообщение") },
            text = { OutlinedTextField(editedText, { editedText = it.take(InputRules.MAX_TEXT_LENGTH) }, Modifier.fillMaxWidth()) },
            confirmButton = { TextButton({
                editing = null
                scope.launch { runCatching { repo.editMessage(chatId, message, editedText) }.onFailure { error = it.userMessage() } }
            }, enabled = editedText.isNotBlank()) { Text("Сохранить") } },
            dismissButton = { TextButton({ editing = null }) { Text("Отмена") } },
        )
    }
    deleting?.let { message ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("Удалить сообщение у всех?") },
            confirmButton = { TextButton({
                deleting = null
                scope.launch { runCatching { repo.deleteMessage(chatId, message) }.onFailure { error = it.userMessage() } }
            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ChatTopBar(
    repo: ChatRepository,
    chatId: String,
    title: String,
    isGroup: Boolean,
    available: Boolean,
    connected: Boolean,
    syncing: Boolean,
    syncError: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCall: (Boolean) -> Unit,
    onMembers: () -> Unit,
    onSearch: () -> Unit,
) {
    val palette = LocalUmbraChatColors.current
    val smokedGlass = LocalUmbraSmokedGlass.current
    val reducedMotion = LocalUmbraReducedMotion.current
    val tokens = com.umbra.app.ui.theme.LocalUmbraAlienTokens.current
    var menu by remember { mutableStateOf(false) }
    // В Alien-режиме статус звучит как бортовой журнал, но смысл строк тот же.
    val status = when {
        syncError != null && tokens.enabled -> "канал потерян"
        syncError != null -> "Нет связи с сервером"
        syncing && tokens.enabled -> "синхронизация…"
        syncing -> "Обновление…"
        !connected && tokens.enabled -> "резервный канал"
        !connected -> "Медленный режим"
        isGroup && tokens.enabled -> "коллектив"
        isGroup -> "Группа"
        tokens.enabled -> "прямой канал"
        else -> "Личный чат"
    }
    val statusColor = when {
        syncError != null -> MaterialTheme.colorScheme.error
        connected && !syncing -> palette.accent
        else -> palette.incomingMeta
    }
    TopAppBar(
        modifier = if (smokedGlass) Modifier.statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .smokedGlassSurface(RoundedCornerShape(24.dp), strong = true) else Modifier,
        windowInsets = if (smokedGlass) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isGroup) GroupAvatar(title, 40.dp) else UserAvatar(repo, chatId, title, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.onIncoming,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Шкала сигнала заменяет точку состояния и видна всегда.
                        if (tokens.enabled) AlienSignalMeter(
                            level = when {
                                syncError != null -> 1
                                syncing -> 2
                                !connected -> 3
                                else -> 4
                            },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        else if (syncError != null || syncing || !connected) {
                            StatusDot(statusColor)
                            Spacer(Modifier.width(6.dp))
                        }
                        Crossfade(targetState = status, animationSpec = tween(if (reducedMotion) 0 else 150), label = "chat-status") { text ->
                            Text(
                                text,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (syncError != null) MaterialTheme.colorScheme.error else palette.incomingMeta,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        },
        actions = {
            IconButton(onSearch) { Icon(Icons.Filled.Search, "Поиск в переписке") }
            if (available) IconButton({ onCall(false) }) {
                Icon(Icons.Filled.Call, if (isGroup) "Групповой звонок" else "Позвонить")
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Действия чата") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (available) DropdownMenuItem(
                        text = { Text(if (isGroup) "Групповой видеозвонок" else "Видеозвонок") },
                        leadingIcon = { Icon(Icons.Filled.Videocam, null) },
                        onClick = { menu = false; onCall(true) },
                    )
                    if (isGroup && available) DropdownMenuItem(
                        text = { Text("Участники группы") }, leadingIcon = { Icon(Icons.Filled.Groups, null) },
                        onClick = { menu = false; onMembers() },
                    )
                    DropdownMenuItem(text = { Text("Обновить сообщения") },
                        leadingIcon = { Icon(Icons.Filled.Refresh, null) }, onClick = { menu = false; onRefresh() })
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = if (smokedGlass) Color.Transparent else palette.bar,
            scrolledContainerColor = if (smokedGlass) Color.Transparent else palette.bar,
            titleContentColor = palette.onIncoming,
            navigationIconContentColor = palette.accent,
            actionIconContentColor = palette.accent,
        ),
    )
}

@Composable
private fun ChatEmptyState(modifier: Modifier = Modifier) {
    val palette = LocalUmbraChatColors.current
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AlienHudLabel("канал свободен")
        Box(
            Modifier.size(64.dp).alienOrbitRing().alienGlow()
                .clip(CircleShape).background(Brush.linearGradient(palette.outgoing)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.Send, null, tint = palette.onOutgoing) }
        Text(
            "Начните разговор",
            style = MaterialTheme.typography.titleMedium,
            color = palette.onIncoming,
        )
        Text(
            "Здесь появятся сообщения, фото и голосовые.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.incomingMeta,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChatComposer(
    input: String,
    onInput: (String) -> Unit,
    enabled: Boolean,
    tooLong: Boolean,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    replyText: String?,
    onCancelReply: () -> Unit,
) {
    val palette = LocalUmbraChatColors.current
    val smokedGlass = LocalUmbraSmokedGlass.current
    val haptics = LocalHapticFeedback.current
    val sendMode = input.isNotBlank()
    val reducedMotion = LocalUmbraReducedMotion.current
    val canSend = enabled && !tooLong
    Surface(color = if (smokedGlass) Color.Transparent else palette.bar, contentColor = palette.onIncoming) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (!replyText.isNullOrBlank()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Reply, null, tint = palette.accent)
                    Spacer(Modifier.width(8.dp))
                    Text(replyText, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = palette.incomingMeta)
                    IconButton(onCancelReply, Modifier.size(40.dp)) { Icon(Icons.Filled.Close, "Отменить ответ") }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Row(
                    Modifier.weight(1f).heightIn(min = 48.dp, max = 148.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(palette.field)
                        .holoEdge(cornerRadius = 26.dp, width = 1.dp)
                        .smokedGlassEdge(RoundedCornerShape(26.dp))
                        .padding(start = 4.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onAttach, enabled = enabled) {
                        Icon(Icons.Filled.AttachFile, "Прикрепить вложение", tint = palette.incomingMeta)
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = onInput,
                        enabled = enabled,
                        modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                        textStyle = LocalUmbraMessageTextStyle.current.copy(color = palette.onIncoming),
                        cursorBrush = SolidColor(palette.accent),
                        maxLines = 6,
                        decorationBox = { inner ->
                            Box {
                                if (input.isEmpty()) Text(
                                    "Сообщение",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = palette.incomingMeta,
                                )
                                inner()
                            }
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(48.dp)
                        .alienGlow(strength = if (canSend) 1.2f else 0.5f)
                        .clip(CircleShape)
                        .background(
                            if (canSend) Brush.linearGradient(palette.outgoing)
                            else SolidColor(palette.field),
                        )
                        .smokedGlassAction(enabled = canSend)
                        .clickable(enabled = canSend) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (sendMode) onSend() else onMic()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(targetState = sendMode, label = "composer-action", transitionSpec = {
                        if (reducedMotion) (EnterTransition.None togetherWith ExitTransition.None).using(null)
                        else (fadeIn(tween(120)) togetherWith fadeOut(tween(90))).using(null)
                    }) { mode ->
                        Icon(
                            if (mode) Icons.AutoMirrored.Filled.Send else Icons.Filled.Mic,
                            if (mode) "Отправить" else "Записать голосовое сообщение",
                            tint = if (canSend) palette.onOutgoing else palette.incomingMeta,
                        )
                    }
                }
            }
            if (tooLong) Text(
                "Максимум без малого 16 000 символов — сократите сообщение",
                Modifier.padding(start = 20.dp, top = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AttachSheet(
    onDismiss: () -> Unit,
    onPickMedia: () -> Unit,
    onRecordVideo: () -> Unit,
    onPickFile: () -> Unit,
) {
    val palette = LocalUmbraChatColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.smokedGlassEdge(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
        containerColor = if (LocalUmbraSmokedGlass.current) com.umbra.app.ui.theme.LocalUmbraVisuals.current.glassStrong
            else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(
                "Что отправить",
                Modifier.padding(start = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                color = palette.onIncoming,
            )
            AttachOption(Icons.Filled.PhotoLibrary, "Фото или видео", "Из галереи устройства", onPickMedia)
            AttachOption(Icons.Filled.Videocam, "Записать видео", "Камера запишет сообщение", onRecordVideo)
            AttachOption(Icons.Filled.InsertDriveFile, "Файл", "Любой документ или архив", onPickFile)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AttachOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val palette = LocalUmbraChatColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(palette.outgoing)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = palette.onOutgoing) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = palette.onIncoming)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = palette.incomingMeta)
        }
    }
}

@Composable
private fun VoiceRecordingBar(state: VoiceRecordingState, sending: Boolean, onCancel: () -> Unit, onSend: () -> Unit) {
    val palette = LocalUmbraChatColors.current
    val smokedGlass = LocalUmbraSmokedGlass.current
    val levels = remember { mutableStateListOf<Float>() }
    LaunchedEffect(state.elapsedMs) {
        levels.add(state.level.coerceIn(0.08f, 1f))
        while (levels.size > 28) levels.removeAt(0)
    }
    val alpha = if (LocalUmbraReducedMotion.current) 1f else {
        val pulse = rememberInfiniteTransition(label = "rec")
        val value by pulse.animateFloat(
            initialValue = 0.35f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "rec-dot",
        )
        value
    }
    Surface(color = if (smokedGlass) Color.Transparent else palette.bar, contentColor = palette.onIncoming) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onCancel, enabled = !sending) {
                Icon(Icons.Filled.Delete, "Отменить запись", tint = MaterialTheme.colorScheme.error)
            }
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(26.dp))
                    .background(palette.field).smokedGlassEdge(RoundedCornerShape(26.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(9.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = alpha)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (state.limitReached) "Максимум 5:00" else voiceDurationText(state.elapsedMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.limitReached) MaterialTheme.colorScheme.error else palette.onIncoming,
                )
                Spacer(Modifier.width(12.dp))
                VoiceWaveform(
                    bars = if (levels.isEmpty()) List(6) { 0.12f } else levels.toList(),
                    progress = 1f,
                    activeColor = palette.accent,
                    inactiveColor = palette.accent,
                    modifier = Modifier.weight(1f).height(26.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(Brush.linearGradient(palette.outgoing))
                    .clickable(enabled = !sending, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить голосовое сообщение", tint = palette.onOutgoing) }
        }
    }
}

@Composable
private fun ForwardMessageDialog(
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
private fun MessageRow(
    repo: ChatRepository,
    item: ChatItem.Bubble,
    isGroup: Boolean,
    senderName: String?,
    playback: VoicePlayback?,
    modifier: Modifier = Modifier,
    onOpen: (UiAttachment, Rect?) -> Unit,
    onShare: (UiAttachment) -> Unit,
    onSave: (UiAttachment) -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: () -> Unit,
    onRetry: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReact: (String) -> Unit,
) {
    val message = item.message
    val palette = LocalUmbraChatColors.current
    val reducedMotion = LocalUmbraReducedMotion.current
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    var menu by remember(message.stableId) { mutableStateOf(false) }
    val outgoing = message.outgoing
    val onBubble = if (outgoing) palette.onOutgoing else palette.onIncoming
    val metaColor = if (outgoing) palette.outgoingMeta else palette.incomingMeta
    val attachment = message.attachment
    val voice = message.voice
    val preview = attachment != null && Attachments.hasPreview(attachment.kind)
    var attachmentBounds by remember(message.stableId) { mutableStateOf<Rect?>(null) }
    val fresh = remember(message.stableId) {
        abs(System.currentTimeMillis() - message.createdAtMillis) < 6_000L
    }
    var landed by remember(message.stableId) { mutableStateOf(reducedMotion || !fresh) }
    LaunchedEffect(message.stableId) { landed = true }
    val landing by animateFloatAsState(
        targetValue = if (landed) 1f else 0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy,
        ),
        label = "message-landing",
    )
    // Свайп по сообщению: вправо — ответить, влево — переслать.
    SwipeMessageActions(
        modifier = modifier.graphicsLayer {
            alpha = landing
            scaleX = 0.96f + 0.04f * landing
            scaleY = 0.96f + 0.04f * landing
            translationY = (1f - landing) * 12.dp.toPx()
        }.fillMaxWidth().padding(top = if (item.first) 10.dp else 2.dp),
        canReply = !message.deleted,
        canForward = !message.deleted && !message.pending,
        accent = palette.accent,
        onReply = onReply,
        onForward = onForward,
    ) { swipe ->
    Row(
        swipe.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!outgoing && isGroup) {
            if (item.last) UserAvatar(repo, message.senderId, senderName ?: "?", 28.dp)
            else Spacer(Modifier.width(28.dp))
            Spacer(Modifier.width(8.dp))
        }
        Box {
            Column(
                Modifier.widthIn(max = 300.dp)
                    .onGloballyPositioned { attachmentBounds = it.boundsInWindow() }
                    .clip(bubbleShape(outgoing, item.first, item.last))
                    .background(
                        if (outgoing) Brush.linearGradient(palette.outgoing)
                        else SolidColor(palette.incoming),
                    )
                    .smokedGlassEdge(bubbleShape(outgoing, item.first, item.last), active = outgoing)
                    // Скан-линии только на своих пузырях: входящий текст остаётся чистым.
                    .holoScanlines(palette.onOutgoing, active = outgoing)
                    .combinedClickable(
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menu = true
                        },
                        // Двойной тап ставит ❤️ — привычный жест из других мессенджеров.
                        onDoubleClick = if (!message.deleted && !message.pending && !message.failed) ({
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onReact("❤️")
                        }) else null,
                        onClick = { if (attachment != null) onOpen(attachment, attachmentBounds) },
                    ),
            ) {
                message.forwardedFrom?.let { from ->
                    Text("Переслано от $from", Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp),
                        style = MaterialTheme.typography.labelSmall, color = metaColor)
                }
                message.replyText?.let { reply ->
                    Text("↩ $reply", Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(10.dp)).background(onBubble.copy(alpha = 0.10f)).padding(8.dp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = onBubble)
                }
                if (item.showName && senderName != null) Text(
                    senderName,
                    Modifier.padding(start = 14.dp, top = 8.dp, end = 14.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.accent,
                )
                when {
                    voice != null -> Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                        VoiceBubble(
                            repo = repo,
                            voice = voice,
                            playback = playback,
                            stableId = message.stableId,
                            contentColor = onBubble,
                            metaColor = metaColor,
                            onTogglePlay = onTogglePlay,
                            onSeek = onSeek,
                            onSpeed = onSpeed,
                        )
                        MetaRow(message, metaColor, Modifier.align(Alignment.End).padding(top = 2.dp))
                    }
                    attachment != null && preview -> Column {
                        AttachmentPhoto(repo, attachment, palette) {
                            if (attachment.isVideo && attachment.durationMs > 0) MediaBadge(
                                voiceDurationText(attachment.durationMs),
                                Modifier.align(Alignment.TopStart).padding(8.dp),
                                icon = Icons.Filled.Videocam,
                            )
                            Row(
                                Modifier.align(Alignment.BottomEnd)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    clockText(message.createdAtMillis),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                )
                                if (outgoing) {
                                    Spacer(Modifier.width(4.dp))
                                    MessageStatus(message.pending, message.failed, Color.White)
                                }
                            }
                        }
                        if (attachment.caption.isNotBlank()) Text(
                            attachment.caption,
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = onBubble,
                            style = LocalUmbraMessageTextStyle.current,
                        )
                    }
                    attachment != null -> Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        FileRow(attachment, onBubble, metaColor, palette)
                        MetaRow(message, metaColor, Modifier.align(Alignment.End).padding(top = 4.dp))
                    }
                    else -> Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                        Text(message.text, color = onBubble, style = LocalUmbraMessageTextStyle.current)
                        MetaRow(message, metaColor, Modifier.align(Alignment.End).padding(top = 2.dp))
                    }
                }
                if (message.reactions.isNotEmpty()) Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) { message.reactions.forEach { (emoji, count) ->
                    if (reducedMotion) Text("$emoji $count", style = MaterialTheme.typography.labelMedium, color = onBubble)
                    else AnimatedContent(targetState = count, label = "reaction-$emoji") { value ->
                        Text("$emoji $value", style = MaterialTheme.typography.labelMedium, color = onBubble)
                    }
                } }
                if (message.failed) Row(
                    Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Replay, null, Modifier.size(16.dp), tint = onBubble)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Повторить отправку",
                        Modifier.clickable(onClick = onRetry),
                        style = MaterialTheme.typography.labelLarge,
                        color = onBubble,
                    )
                }
            }
            val wideContext = LocalConfiguration.current.screenWidthDp >= 840
            val contextActions: @Composable ColumnScope.() -> Unit = {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Действия", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton({ menu = false }) { Icon(Icons.Filled.Close, "Закрыть панель") }
                }
                Text(
                    message.text,
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.incomingMeta,
                )
                if (!message.deleted) DropdownMenuItem(
                    text = { Text("Ответить") },
                    leadingIcon = { Icon(Icons.Filled.Reply, null) },
                    onClick = { menu = false; onReply() },
                )
                if (!message.pending && !message.deleted) DropdownMenuItem(
                    text = { Text("Переслать") },
                    leadingIcon = { Icon(Icons.Filled.Forward, null) },
                    onClick = { menu = false; onForward() },
                )
                if (message.outgoing && !message.pending && !message.deleted && voice == null && attachment == null) DropdownMenuItem(
                    text = { Text("Редактировать") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    onClick = { menu = false; onEdit() },
                )
                if (message.outgoing && !message.pending && !message.deleted) DropdownMenuItem(
                    text = { Text("Удалить у всех") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { menu = false; onDelete() },
                )
                if (!message.deleted && !message.pending && !message.failed) Column(Modifier.padding(horizontal = 8.dp)) {
                    listOf("👍", "❤️", "😂", "😮", "😢").chunked(3).forEach { reactions ->
                        Row { reactions.forEach { emoji ->
                            TextButton({
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menu = false
                                onReact(emoji)
                            }, contentPadding = PaddingValues(4.dp)) { Text(emoji) }
                        } }
                    }
                }
                if (!message.deleted && attachment == null && voice == null && message.text.isNotBlank()) DropdownMenuItem(
                    text = { Text("Копировать") },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                    onClick = {
                        clipboard.setText(AnnotatedString(message.text))
                        menu = false
                    },
                )
                if (attachment != null) {
                    DropdownMenuItem(
                        text = { Text("Открыть") },
                        leadingIcon = { Icon(Icons.Filled.OpenInNew, null) },
                        onClick = { menu = false; onOpen(attachment, attachmentBounds) },
                    )
                    DropdownMenuItem(
                        text = { Text("Поделиться") },
                        leadingIcon = { Icon(Icons.Filled.Share, null) },
                        onClick = { menu = false; onShare(attachment) },
                    )
                    DropdownMenuItem(
                        text = { Text("Сохранить в файлы") },
                        leadingIcon = { Icon(Icons.Filled.Download, null) },
                        onClick = { menu = false; onSave(attachment) },
                    )
                }
                if (message.failed) DropdownMenuItem(
                    text = { Text("Повторить отправку") },
                    leadingIcon = { Icon(Icons.Filled.Replay, null) },
                    onClick = { menu = false; onRetry() },
                )
            }
            if (menu) {
                if (wideContext) Dialog(
                    onDismissRequest = { menu = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    Box(
                        Modifier.fillMaxSize().padding(end = 20.dp, top = 24.dp, bottom = 24.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        GlassPanel(Modifier.width(380.dp).fillMaxHeight(), strong = true) {
                            Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                    .navigationBarsPadding().padding(vertical = 16.dp),
                                content = contextActions,
                            )
                        }
                    }
                } else ModalBottomSheet(
                    onDismissRequest = { menu = false },
                    containerColor = palette.bar,
                ) {
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp),
                        content = contextActions,
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun MetaRow(message: UiMessage, tint: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(clockText(message.createdAtMillis) + if (message.edited) " · изменено" else "", style = MaterialTheme.typography.labelSmall, color = tint)
        if (message.outgoing) {
            Spacer(Modifier.width(4.dp))
            MessageStatus(
                message.pending,
                message.failed,
                if (message.failed) MaterialTheme.colorScheme.error else tint,
            )
        }
    }
}

@Composable
private fun FileRow(attachment: UiAttachment, contentColor: Color, metaColor: Color, palette: UmbraChatColors) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(palette.field),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.InsertDriveFile, null, tint = palette.accent) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.widthIn(max = 200.dp)) {
            Text(attachment.name, color = contentColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                Attachments.sizeText(attachment.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = metaColor,
            )
        }
    }
}

@Composable
private fun VoiceBubble(
    repo: ChatRepository,
    voice: VoiceMessage,
    playback: VoicePlayback?,
    stableId: String,
    contentColor: Color,
    metaColor: Color,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: () -> Unit,
) {
    val palette = LocalUmbraChatColors.current
    val playing = playback?.playing == true
    val loading = playback?.loading == true
    val total = (playback?.durationMs ?: voice.durationMs).coerceAtLeast(1L)
    val position = playback?.positionMs ?: 0L
    val ready = voice.mediaId != null || voice.localPath != null
    val fallbackBars = remember(stableId) { waveformBars(stableId, 48) }
    val bars by produceState(fallbackBars, stableId, voice.mediaId, voice.localPath) {
        value = runCatching { repo.voiceWaveform(voice, 48) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: fallbackBars
    }
    val fraction = (position.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(42.dp).clip(CircleShape)
                .background(contentColor.copy(alpha = 0.16f))
                .smokedGlassAction(enabled = ready && !loading)
                .clickable(enabled = ready && !loading, onClick = onTogglePlay),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = contentColor)
            else Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (playing) "Пауза" else "Прослушать голосовое сообщение",
                tint = contentColor,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(154.dp)) {
            VoiceWaveform(
                bars = bars,
                progress = fraction,
                activeColor = if (LocalUmbraSmokedGlass.current) palette.accent else contentColor,
                inactiveColor = metaColor.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth().height(28.dp),
                onSeek = if (playback != null && !loading) ({ value -> onSeek((value * total).toLong()) }) else null,
            )
            Text(
                when {
                    loading -> "Загрузка записи…"
                    playback != null -> voiceDurationText(position) + " / " + voiceDurationText(total)
                    else -> voiceDurationText(voice.durationMs)
                },
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = metaColor,
            )
        }
        if (playback != null && !loading) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.clip(CircleShape).background(contentColor.copy(alpha = 0.16f))
                    .clickable(onClick = onSpeed).padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(voiceSpeedLabel(playback.speed), style = MaterialTheme.typography.labelSmall, color = contentColor)
            }
        }
    }
}

/** Цикл скоростей по нажатию: 1× → 1,5× → 2× → 1×. */
private val VOICE_SPEEDS = listOf(1f, 1.5f, 2f)

private fun nextVoiceSpeed(current: Float): Float {
    val index = VOICE_SPEEDS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return VOICE_SPEEDS[(index + 1) % VOICE_SPEEDS.size]
}

private fun voiceSpeedLabel(speed: Float): String = when {
    speed >= 1.99f -> "2×"
    speed >= 1.49f -> "1,5×"
    else -> "1×"
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

/**
 * Кого зовём в групповой звонок. Соединения идут напрямую между телефонами,
 * поэтому выбрать можно не больше трёх собеседников.
 */
@Composable
private fun GroupCallDialog(
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
private fun ScrollToBottomPill(
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
