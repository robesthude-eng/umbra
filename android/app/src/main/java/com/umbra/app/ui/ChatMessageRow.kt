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
 * Пузырь сообщения и его части: метаданные, файлы, голосовые,
 * прогресс загрузки. Вынесено из ChatView.kt.
 */

@Composable
internal fun MessageRow(
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
    // Процент отдачи файла на сервер — только для своих недошлённых сообщений.
    val mediaProgress by repo.mediaProgress.collectAsState()
    val uploadScope = rememberCoroutineScope()
    val uploadPercent = mediaProgress[message.stableId]
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
                        // Двойной тап ставит быструю реакцию — привычный жест из других мессенджеров.
                        onDoubleClick = if (!message.deleted && !message.pending && !message.failed) ({
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onReact(Reactions.QUICK)
                        }) else null,
                        // Обычное касание: фото/видео открывается, текст показывает панель реакций.
                        onClick = {
                            if (attachment != null) onOpen(attachment, attachmentBounds)
                            else if (!message.deleted) menu = true
                        },
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
                                    MessageStatus(message.pending, message.failed, Color.White, read = message.read)
                                }
                            }
                        }
                        if (uploadPercent != null) UploadProgressRow(
                            uploadPercent,
                            onBubble,
                            metaColor,
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            onCancel = { uploadScope.launch { runCatching { repo.cancelUpload(message.stableId) } } },
                        )
                        if (attachment.caption.isNotBlank()) Text(
                            attachment.caption,
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = onBubble,
                            style = LocalUmbraMessageTextStyle.current,
                        )
                    }
                    attachment != null -> Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        FileRow(attachment, onBubble, metaColor, palette)
                        if (uploadPercent != null) UploadProgressRow(
                            uploadPercent,
                            onBubble,
                            metaColor,
                            Modifier.padding(top = 6.dp),
                            onCancel = { uploadScope.launch { runCatching { repo.cancelUpload(message.stableId) } } },
                        )
                        MetaRow(message, metaColor, Modifier.align(Alignment.End).padding(top = 4.dp))
                    }
                    else -> Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                        Text(message.text, color = onBubble, style = LocalUmbraMessageTextStyle.current)
                        LinkPreviewCard(repo, message.text, onBubble, metaColor)
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
                    Reactions.ALL.chunked(4).forEach { reactions ->
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

/**
 * Индикатор загрузки вложения: полоса + живые проценты 0…100.
 */
@Composable
internal fun UploadProgressRow(
    percent: Int,
    contentColor: Color,
    metaColor: Color,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    val reducedMotion = LocalUmbraReducedMotion.current
    val animated by animateFloatAsState(
        targetValue = (percent.coerceIn(0, 100)) / 100f,
        animationSpec = tween(if (reducedMotion) 0 else 180),
        label = "upload-progress",
    )
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = contentColor,
            trackColor = contentColor.copy(alpha = 0.25f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$percent%",
            style = MaterialTheme.typography.labelSmall,
            color = metaColor,
        )
        if (onCancel != null) IconButton(onCancel, Modifier.size(28.dp)) {
            Icon(Icons.Filled.Close, "Отменить загрузку", Modifier.size(16.dp), tint = metaColor)
        }
    }
}

@Composable
internal fun MetaRow(message: UiMessage, tint: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(clockText(message.createdAtMillis) + if (message.edited) " · изменено" else "", style = MaterialTheme.typography.labelSmall, color = tint)
        if (message.outgoing) {
            Spacer(Modifier.width(4.dp))
            MessageStatus(
                message.pending,
                message.failed,
                if (message.failed) MaterialTheme.colorScheme.error else tint,
                read = message.read,
            )
            // В группе важно не только «прочитано», но и сколькими людьми.
            if (message.readBy > 1) {
                Spacer(Modifier.width(3.dp))
                Text(
                    message.readBy.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                )
            }
        }
    }
}

@Composable
internal fun FileRow(attachment: UiAttachment, contentColor: Color, metaColor: Color, palette: UmbraChatColors) {
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
internal fun VoiceBubble(
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
internal val VOICE_SPEEDS = listOf(1f, 1.5f, 2f)

internal fun nextVoiceSpeed(current: Float): Float {
    val index = VOICE_SPEEDS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return VOICE_SPEEDS[(index + 1) % VOICE_SPEEDS.size]
}

internal fun voiceSpeedLabel(speed: Float): String = when {
    speed >= 1.99f -> "2×"
    speed >= 1.49f -> "1,5×"
    else -> "1×"
}
