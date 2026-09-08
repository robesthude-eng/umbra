package com.umbra.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.umbra.app.data.media.MediaInfo
import com.umbra.app.data.repo.UiMessage
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(container: AppContainer, chatId: String, onBack: () -> Unit) {
    val vm: ChatViewModel = viewModel(factory = ChatViewModel.Factory(container, chatId))
    val messages by vm.messages.collectAsState()
    val sending by vm.sending.collectAsState()
    val error by vm.error.collectAsState()
    val sendingMedia by vm.sendingMedia.collectAsState()
    val mediaError by vm.mediaError.collectAsState()
    val listState = rememberLazyListState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Пикеры медиа: фото/видео (Photo Picker) и произвольный файл (SAF).
    // Разрешения не нужны: системный пикер отдаёт временный grant на Uri.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { vm.sendMedia(it) } }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.sendMedia(it) } }

    // Сохранение расшифрованного медиа в выбранное пользователем место (SAF).
    var pendingSave by remember { mutableStateOf<MediaInfo?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { dest ->
        val info = pendingSave
        pendingSave = null
        if (dest != null && info != null) {
            scope.launch {
                val result = runCatching {
                    val file = vm.fetchMedia(info)
                    val out = context.contentResolver.openOutputStream(dest)
                        ?: throw java.io.IOException("не удалось открыть выходной поток")
                    out.use { o -> file.inputStream().use { it.copyTo(o) } }
                }
                saveError = result.exceptionOrNull()?.let { it.message ?: "не удалось сохранить файл" }
            }
        }
    }

    // Автопрокрутка к последнему сообщению.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    val banner = mediaError ?: saveError
    val busy = sending || sendingMedia

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Диалог") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (banner != null) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = banner,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            IconButton(onClick = {
                                vm.dismissMediaError()
                                saveError = null
                            }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Скрыть",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
                if (sendingMedia) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                MessageInput(
                    sending = busy,
                    onSend = vm::send,
                    onPickPhoto = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                        )
                    },
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg, vm, onRetry = { vm.retry(msg.id) }, onSaveMedia = { info ->
                    saveError = null
                    pendingSave = info
                    saveLauncher.launch(info.name ?: "umbra-media")
                })
            }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: UiMessage,
    vm: ChatViewModel,
    onRetry: () -> Unit,
    onSaveMedia: (MediaInfo) -> Unit,
) {
    val contentColor = if (msg.outgoing) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (msg.outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (msg.media != null) {
                        MediaContent(vm, msg.media, onSave = { onSaveMedia(msg.media) })
                        Spacer(Modifier.size(4.dp))
                    }
                    if (msg.text.isNotBlank() || msg.media == null) {
                        Text(
                            text = msg.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = contentColor,
                        )
                    }
                    msg.expiresAt?.let {
                        Text(
                            text = "⏱",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (msg.outgoing) {
                        Text(when (msg.deliveryState) {
                            "pending" -> "Ожидает отправки"
                            "failed" -> "Не отправлено"
                            else -> "Отправлено"
                        }, style = MaterialTheme.typography.labelSmall)
                        if (msg.deliveryState == "failed") TextButton(
                            onClick = onRetry,
                            colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                        ) { Text("Повторить") }
                    }
                    msg.error?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

/**
 * Содержимое вложения: скачивает ciphertext, расшифровывает в приватный кэш
 * и рисует фото (декодирование с inSampleSize, чтобы не держать полноразмерный
 * bitmap в памяти) или строку файла с кнопкой сохранения.
 */
@Composable
private fun MediaContent(vm: ChatViewModel, info: MediaInfo, onSave: () -> Unit) {
    var file by remember(info.id) { mutableStateOf<File?>(null) }
    var failed by remember(info.id) { mutableStateOf<String?>(null) }
    var bitmap by remember(info.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(info.id) {
        runCatching { withContext(Dispatchers.IO) { vm.fetchMedia(info) } }
            .onSuccess { f ->
                file = f
                if (info.kind == MediaInfo.KIND_PHOTO && info.contentType.startsWith("image/")) {
                    bitmap = withContext(Dispatchers.IO) { decodeSampledBitmap(f, MAX_IMAGE_PX) }
                }
            }
            .onFailure { failed = it.message ?: "ошибка скачивания" }
    }

    when {
        failed != null -> Text(
            text = "⚠ медиа недоступно: $failed",
            style = MaterialTheme.typography.bodySmall,
        )
        file == null -> Box(
            modifier = Modifier.size(96.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        bitmap != null -> Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = info.name ?: "фото",
            modifier = Modifier
                .sizeIn(maxWidth = 280.dp, maxHeight = 280.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit,
        )
        else -> MediaFileRow(info = info, onSave = onSave)
    }
}

@Composable
private fun MediaFileRow(info: MediaInfo, onSave: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (info.kind == MediaInfo.KIND_PHOTO) Icons.Filled.Image else Icons.Filled.InsertDriveFile,
            contentDescription = null,
        )
        Column(
            modifier = Modifier.padding(horizontal = 8.dp).widthIn(max = 200.dp),
        ) {
            Text(
                text = info.name ?: "файл",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${humanSize(info.size)} · ${info.contentType}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onSave) {
            Icon(Icons.Filled.Download, contentDescription = "Сохранить")
        }
    }
}

/** Декодирует фото с понижающим inSampleSize: максимум ~MAX_IMAGE_PX по большей стороне. */
private fun decodeSampledBitmap(file: File, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDimension &&
        bounds.outHeight / (sample * 2) >= maxDimension
    ) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MiB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

private const val MAX_IMAGE_PX = 1080

@Composable
private fun MessageInput(
    sending: Boolean,
    onSend: (String, () -> Unit) -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var attachExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            IconButton(onClick = { attachExpanded = true }, enabled = !sending) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Прикрепить")
            }
            DropdownMenu(
                expanded = attachExpanded,
                onDismissRequest = { attachExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Фото / видео") },
                    leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                    onClick = {
                        attachExpanded = false
                        onPickPhoto()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Файл") },
                    leadingIcon = { Icon(Icons.Filled.InsertDriveFile, contentDescription = null) },
                    onClick = {
                        attachExpanded = false
                        onPickFile()
                    },
                )
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Сообщение") },
            maxLines = 4,
            enabled = !sending,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = {
                onSend(text) { text = "" }
            },
            enabled = text.isNotBlank() && !sending,
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
        }
    }
}
