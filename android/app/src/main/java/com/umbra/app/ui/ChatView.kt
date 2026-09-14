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

/** Сообщения одного автора с разницей меньше этого склеиваются в группу. */
internal const val GROUP_WINDOW_MS = 5 * 60 * 1000L

/** Потолок звонка без серверного микшера: четверо участников, то есть трое приглашённых. */
internal const val MAX_GROUP_CALL_PEERS = 3

/** Элемент ленты: разделитель дня либо сообщение с пометками группировки. */
internal sealed interface ChatItem {
    data class Day(val key: String, val label: String) : ChatItem
    data class Bubble(
        val message: UiMessage,
        val first: Boolean,
        val last: Boolean,
        val showName: Boolean,
    ) : ChatItem
}

internal fun buildChatItems(messages: List<UiMessage>, isGroup: Boolean): List<ChatItem> {
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
    val drafts = container.drafts
    // Черновик подставляется один раз при открытии чата.
    var input by rememberSaveable(chatId) { mutableStateOf(drafts.get(chatId)) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showMembers by rememberSaveable(chatId) { mutableStateOf(false) }
    var showGroupCall by rememberSaveable(chatId) { mutableStateOf(false) }
    var groupCallVideo by rememberSaveable(chatId) { mutableStateOf(false) }
    var replyingTo by remember(chatId) { mutableStateOf<UiMessage?>(null) }
    var forwarding by remember(chatId) { mutableStateOf<UiMessage?>(null) }
    var editing by remember(chatId) { mutableStateOf<UiMessage?>(null) }
    var deleting by remember(chatId) { mutableStateOf<UiMessage?>(null) }
    var showMedia by rememberSaveable(chatId) { mutableStateOf(false) }
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
    // Чат открыт — сообщаем о прочтении и забираем чужие курсоры (push мог не дойти).
    // В группах тот же запрос отдаёт список тех, кто уже прочитал.
    if (available) LaunchedEffect(chatId, latestId) {
        runCatching { repo.markRead(chatId) }
        runCatching { repo.refreshRead(chatId) }
    }
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
    // Черновик сохраняется с небольшой задержкой, а собеседник видит «печатает…».
    LaunchedEffect(chatId, input) {
        if (input.isNotBlank() && available) runCatching { repo.notifyTyping(chatId) }
        delay(600)
        drafts.put(chatId, input)
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
    val appearance by container.uiPreferences.state.collectAsState()
    // Переключатель из панели вложений действует только на этот чат.
    var sendOriginal by rememberSaveable(chatId) { mutableStateOf(false) }
    val sendQuality = if (sendOriginal) MediaSendQuality.ORIGINAL else appearance.mediaQuality
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
                        quality = sendQuality,
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
                repo.sendAttachment(chatId, Attachments.uriFor(context, file), input.trim(), replyingTo, sendQuality)
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
                    onMedia = { showMedia = true },
                    onRetryAll = { scope.launch { runCatching { repo.retryAllFailed() } } },
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
        sendOriginal = sendOriginal || appearance.mediaQuality == MediaSendQuality.ORIGINAL,
        onToggleOriginal = { sendOriginal = it },
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
    // Галерея чата: клик открывает файл тем же путём, что и из переписки.
    if (showMedia) ChatMediaDialog(
        repo = repo,
        messages = messages,
        onOpen = { message ->
            showMedia = false
            message.attachment?.let { openAttachment(it) }
        },
        onDismiss = { showMedia = false },
    )
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
    onMedia: () -> Unit,
    onRetryAll: () -> Unit,
) {
    val palette = LocalUmbraChatColors.current
    val smokedGlass = LocalUmbraSmokedGlass.current
    val reducedMotion = LocalUmbraReducedMotion.current
    val tokens = com.umbra.app.ui.theme.LocalUmbraAlienTokens.current
    var menu by remember { mutableStateOf(false) }
    // Присутствие собеседника: обновляется событиями ws и редким опросом.
    val presenceMap by repo.presence.collectAsState()
    val peer = if (isGroup) null else presenceMap[chatId]
    val lastSeen = peer?.takeIf { !it.hidden && !it.online }?.lastSeenAtMillis?.let { lastSeenText(it) }
    // Статус приходит пушом по ws; опрос раз в две минуты — страховка от потери события.
    if (!isGroup) LaunchedEffect(chatId) {
        while (true) {
            runCatching { repo.refreshPresence(chatId) }
            delay(120_000L)
        }
    }
    // «Печатает…» приходит пушом; опрос остаётся запасным путём для старого сервера.
    val typingMap by repo.typing.collectAsState()
    val typingHere = typingMap[chatId].orEmpty()
    if (available) LaunchedEffect(chatId) {
        while (true) {
            runCatching { repo.refreshTyping(chatId) }
            delay(10_000L)
        }
    }
    // В Alien-режиме статус звучит как бортовой журнал, но смысл строк тот же.
    val status = when {
        syncError != null && tokens.enabled -> "канал потерян"
        syncError != null -> "Нет связи с сервером"
        syncing && tokens.enabled -> "синхронизация…"
        syncing -> "Обновление…"
        !connected && tokens.enabled -> "резервный канал"
        !connected -> "Медленный режим"
        typingHere.isNotEmpty() && isGroup -> if (typingHere.size == 1) "печатает…" else "печатают: ${typingHere.size}"
        typingHere.isNotEmpty() -> "печатает…"
        peer?.online == true && !isGroup -> "в сети"
        lastSeen != null -> lastSeen
        isGroup && tokens.enabled -> "коллектив"
        isGroup -> "Группа"
        tokens.enabled -> "прямой канал"
        else -> "Личный чат"
    }
    val statusColor = when {
        syncError != null -> MaterialTheme.colorScheme.error
        typingHere.isNotEmpty() -> palette.accent
        !isGroup && peer?.online == true -> palette.accent
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
                    DropdownMenuItem(
                        text = { Text("Повторить всё неотправленное") },
                        leadingIcon = { Icon(Icons.Filled.Replay, null) },
                        onClick = { menu = false; onRetryAll() },
                    )
                    DropdownMenuItem(
                        text = { Text("Медиа и файлы") },
                        leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) },
                        onClick = { menu = false; onMedia() },
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
