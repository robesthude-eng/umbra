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
 * Панель ввода переписки: текстовое поле, шторка вложений и запись голоса.
 * Вынесено из ChatView.kt, чтобы экран чата оставался читаемым.
 */

@Composable
internal fun ChatComposer(
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
internal fun AttachSheet(
    onDismiss: () -> Unit,
    sendOriginal: Boolean,
    onToggleOriginal: (Boolean) -> Unit,
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
            HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            // Файлы (документы) всегда уходят байт в байт; переключатель касается фото и видео.
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Отправлять без сжатия",
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.onIncoming,
                    )
                    Text(
                        if (sendOriginal) "Фото и видео уйдут в исходном качестве — дольше и тяжелее"
                        else "Фото сжимается до 2048 px, видео — до 720p",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.incomingMeta,
                    )
                }
                Switch(checked = sendOriginal, onCheckedChange = onToggleOriginal)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun AttachOption(
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
internal fun VoiceRecordingBar(state: VoiceRecordingState, sending: Boolean, onCancel: () -> Unit, onSend: () -> Unit) {
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
