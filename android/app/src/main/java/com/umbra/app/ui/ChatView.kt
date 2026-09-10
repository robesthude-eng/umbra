@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.umbra.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.umbra.app.ui.theme.UmbraColors
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ChatView(container: AppContainer, chatId: String, onBack: () -> Unit) {
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
    var input by rememberSaveable(chatId) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showMembers by rememberSaveable(chatId) { mutableStateOf(false) }
    var showGroupCall by rememberSaveable(chatId) { mutableStateOf(false) }
    var groupCallVideo by rememberSaveable(chatId) { mutableStateOf(false) }
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
                listState.animateScrollToItem(0)
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
    fun sendPicked(uri: Uri?) {
        if (uri == null || sending) return
        sending = true; error = null
        scope.launch {
            try {
                repo.sendAttachment(chatId, uri)
                listState.animateScrollToItem(0)
            } catch (e: Exception) { error = e.userMessage() }
            finally { sending = false }
        }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> sendPicked(uri) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> sendPicked(uri) }
    // Запись видеосообщения прямо из чата: камера пишет в приватный каталог,
    // оттуда запись уходит обычным вложением, а временный файл удаляется.
    var captureTarget by remember { mutableStateOf<File?>(null) }
    fun sendCaptured(file: File) {
        if (sending) return
        sending = true; error = null
        scope.launch {
            try {
                repo.sendAttachment(chatId, Attachments.uriFor(context, file))
                listState.animateScrollToItem(0)
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
    // Фото и видео теперь открываются внутри Umbra (см. MediaViewer.kt):
    // раньше чат всегда отдавал файл стороннему приложению.
    var viewing by remember(chatId) { mutableStateOf<UiAttachment?>(null) }
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
    fun openAttachment(attachment: UiAttachment) {
        error = null
        if (attachment.isImage || attachment.isVideo) viewing = attachment else openExternally(attachment)
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

    BackHandler(onBack = onBack)
    Scaffold(containerColor = Color.Transparent, modifier = Modifier.imePadding(), topBar = {
        TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isGroup) Avatar(repo, null, title, 36.dp) else UserAvatar(repo, chatId, title, 36.dp)
                Spacer(Modifier.width(10.dp))
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } }, actions = {
            // Групповой звонок идёт напрямую между телефонами, поэтому до четырёх участников.
            if (available) {
                IconButton({ if (isGroup) { groupCallVideo = false; showGroupCall = true } else startCall(false) }) {
                    Icon(Icons.Filled.Call, if (isGroup) "Групповой звонок" else "Позвонить")
                }
                IconButton({ if (isGroup) { groupCallVideo = true; showGroupCall = true } else startCall(true) }) {
                    Icon(Icons.Filled.Videocam, if (isGroup) "Групповой видеозвонок" else "Видеозвонок")
                }
            }
            if (isGroup && available) IconButton({ showMembers = true }) { Icon(Icons.Filled.Groups, "Участники группы") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            SyncBanner(repo)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) Text("Здесь появятся сообщения", Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(messages.asReversed(), key = { it.stableId }) { message ->
                        MessageBubble(
                            repo,
                            message,
                            if (isGroup && !message.outgoing) users[message.senderId]?.fullName() ?: "Участник" else null,
                            playback?.takeIf { it.key == message.stableId },
                            onOpen = { openAttachment(it) },
                            onShare = { shareAttachment(it) },
                            onSave = { saveAttachment(it) },
                            onTogglePlay = {
                                val voice = message.voice
                                if (voice != null) scope.launch {
                                    try { player.toggle(message.stableId, voice.mediaId, voice.localPath, voice.durationMs) }
                                    catch (e: Exception) { error = e.userMessage() }
                                }
                            },
                            onSeek = { positionMs ->
                                scope.launch {
                                    try { player.seekTo(message.stableId, positionMs) }
                                    catch (e: Exception) { error = e.userMessage() }
                                }
                            },
                            onSpeed = {
                                scope.launch {
                                    try { player.setSpeed(nextVoiceSpeed(playback?.speed ?: 1f)) }
                                    catch (e: Exception) { error = e.userMessage() }
                                }
                            },
                        ) {
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
            val activeRecording = recording
            if (activeRecording != null) {
                VoiceRecordingBar(activeRecording, sending, onCancel = { cancelRecording() }, onSend = { sendRecording() })
            } else {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                    Box {
                        IconButton(enabled = !sending && available, onClick = { showAttachMenu = true }) {
                            Icon(Icons.Filled.AttachFile, "Прикрепить вложение")
                        }
                        DropdownMenu(showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                            DropdownMenuItem(text = { Text("Фото или видео") }, onClick = {
                                showAttachMenu = false
                                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            })
                            DropdownMenuItem(text = { Text("Записать видео") }, onClick = {
                                showAttachMenu = false
                                requestVideoCapture()
                            })
                            DropdownMenuItem(text = { Text("Файл") }, onClick = {
                                showAttachMenu = false
                                pickFile.launch(arrayOf("*/*"))
                            })
                        }
                    }
                    OutlinedTextField(
                        input, { input = it; error = null }, Modifier.weight(1f), enabled = !sending && available,
                        placeholder = { Text("Сообщение…") }, maxLines = 5,
                        isError = input.length > InputRules.MAX_TEXT_LENGTH,
                        supportingText = if (input.length > InputRules.MAX_TEXT_LENGTH) ({ Text("Максимум 16 000 символов") }) else null,
                    )
                    Spacer(Modifier.width(6.dp))
                    if (input.isBlank()) {
                        // Пустое поле — микрофон. Нажатие, а не удержание: так удобнее
                        // для долгих записей и для людей с ограниченной моторикой.
                        FilledIconButton(enabled = !sending && available, onClick = { requestRecording() }) {
                            Icon(Icons.Filled.Mic, "Записать голосовое сообщение")
                        }
                    } else {
                        FilledIconButton(enabled = !sending && available && input.length <= InputRules.MAX_TEXT_LENGTH, onClick = {
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
        }
    }
    val viewed = viewing
    if (viewed != null) AttachmentViewerDialog(
        repo = repo,
        attachment = viewed,
        onDismiss = { viewing = null },
        onOpenExternally = {
            viewing = null
            openExternally(viewed)
        },
        onShare = { shareAttachment(viewed) },
        onSave = { saveAttachment(viewed) },
    )
    if (showMembers) GroupMembersDialog(repo, chatId) { showMembers = false }
    if (showGroupCall) GroupCallDialog(repo, chatId, groupCallVideo, { showGroupCall = false }) { invited ->
        showGroupCall = false
        startGroupCall(invited, groupCallVideo)
    }
}

@Composable
private fun VoiceRecordingBar(state: VoiceRecordingState, sending: Boolean, onCancel: () -> Unit, onSend: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onCancel, enabled = !sending) { Icon(Icons.Filled.Delete, "Отменить запись") }
        Column(Modifier.weight(1f)) {
            Text(
                if (state.limitReached) "Максимум 5:00 — отправьте или запишите заново" else "Запись… ${voiceDurationText(state.elapsedMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.limitReached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            LinearProgressIndicator(
                progress = { state.level.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        FilledIconButton(onSend, enabled = !sending) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить голосовое сообщение") }
    }
}

@Composable
private fun VoiceBubble(
    voice: VoiceMessage,
    playback: VoicePlayback?,
    textColor: Color,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: () -> Unit,
) {
    val playing = playback?.playing == true
    val loading = playback?.loading == true
    val total = (playback?.durationMs ?: voice.durationMs).coerceAtLeast(1L)
    val position = playback?.positionMs ?: 0L
    val ready = voice.mediaId != null || voice.localPath != null
    // Пока палец на ползунке, показываем его позицию, а не тикающую позицию плеера.
    var dragFraction by remember(playback?.key) { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: (position.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledIconButton(onTogglePlay, enabled = !loading && ready) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (playing) "Пауза" else "Прослушать голосовое сообщение",
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(168.dp)) {
            Slider(
                value = fraction,
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    dragFraction?.let { onSeek((it * total).toLong()) }
                    dragFraction = null
                },
                enabled = playback != null && !loading,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                when {
                    loading -> "Загрузка записи…"
                    playback != null -> "${voiceDurationText(position)} / ${voiceDurationText(total)}"
                    else -> "Голосовое · ${voiceDurationText(voice.durationMs)}"
                },
                style = MaterialTheme.typography.labelSmall, color = textColor,
            )
        }
        if (playback != null && !loading) TextButton(onSpeed, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(voiceSpeedLabel(playback.speed), style = MaterialTheme.typography.labelSmall, color = textColor)
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
private fun MessageBubble(
    repo: ChatRepository,
    message: UiMessage,
    senderName: String?,
    playback: VoicePlayback?,
    onOpen: (UiAttachment) -> Unit,
    onShare: (UiAttachment) -> Unit,
    onSave: (UiAttachment) -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: () -> Unit,
    onRetry: () -> Unit,
) {
    val textColor = if (message.outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
        senderName?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        Surface(shape = RoundedCornerShape(18.dp), color = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                val voice = message.voice
                val attachment = message.attachment
                when {
                    voice != null -> VoiceBubble(voice, playback, textColor, onTogglePlay, onSeek, onSpeed)
                    attachment != null -> AttachmentBubble(
                        repo, attachment, textColor,
                        onOpen = { onOpen(attachment) },
                        onShare = { onShare(attachment) },
                        onSave = { onSave(attachment) },
                    )
                    else -> SelectionContainer { Text(message.text, color = textColor) }
                }
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

/**
 * Пузырь вложения: у фото и видео сверху миниатюра, у файла — строка с именем
 * и размером. Действия одинаковые для всех типов: открыть, поделиться, сохранить.
 */
@Composable
private fun AttachmentBubble(
    repo: ChatRepository,
    attachment: UiAttachment,
    textColor: Color,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    val preview = Attachments.hasPreview(attachment.kind)
    // Миниатюра готовится вне кадра отрисовки и переиспользуется из кэша репозитория.
    var attempt by remember(attachment.mediaId, attachment.localPath) { mutableIntStateOf(0) }
    var thumbFailed by remember(attachment.mediaId, attachment.localPath) { mutableStateOf(false) }
    val thumb by produceState<Bitmap?>(null, attachment.mediaId, attachment.localPath, attempt) {
        if (!preview) {
            value = null
            return@produceState
        }
        value = null
        thumbFailed = false
        val loaded = runCatching { repo.attachmentThumbnail(attachment) }.getOrNull()
        // Раньше при ошибке загрузки оставался бесконечный индикатор.
        thumbFailed = loaded == null
        value = loaded
    }
    Column(Modifier.widthIn(max = 272.dp)) {
        if (preview) {
            // Собственные пропорции кадра, но без крайностей панорам и скриншотов.
            val ratio = if (attachment.width > 0 && attachment.height > 0)
                (attachment.width.toFloat() / attachment.height.toFloat()).coerceIn(0.6f, 1.8f) else 1.4f
            Box(
                Modifier.fillMaxWidth().aspectRatio(ratio)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onOpen),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = thumb
                when {
                    bitmap != null -> Image(
                        bitmap.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                    )
                    // Ошибка и кнопка повтора вместо бесконечного кружка.
                    thumbFailed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Превью не загрузилось",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        TextButton({ attempt++ }) { Text("Повторить") }
                    }
                    else -> CircularProgressIndicator(Modifier.size(28.dp))
                }
                if (attachment.isVideo && bitmap != null) Icon(
                    Icons.Filled.PlayCircle, contentDescription = "Воспроизвести",
                    modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            val meta = listOf(
                if (attachment.isVideo && attachment.durationMs > 0) voiceDurationText(attachment.durationMs) else "",
                Attachments.sizeText(attachment.sizeBytes),
            ).filter { it.isNotBlank() }.joinToString(" · ")
            Text(meta, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = textColor)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.InsertDriveFile, contentDescription = null, tint = textColor)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(attachment.name, color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(Attachments.sizeText(attachment.sizeBytes), style = MaterialTheme.typography.labelSmall, color = textColor)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onOpen) { Text("Открыть", color = textColor) }
            IconButton(onShare) { Icon(Icons.Filled.Share, contentDescription = "Поделиться", tint = textColor) }
            IconButton(onSave) { Icon(Icons.Filled.Download, contentDescription = "Сохранить в файлы", tint = textColor) }
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

/** Потолок звонка без серверного микшера: четверо участников, то есть трое приглашённых. */
private const val MAX_GROUP_CALL_PEERS = 3

private val timeFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())
private fun timeText(millis: Long) = if (millis <= 0) "" else timeFormat.format(Instant.ofEpochMilli(millis))
