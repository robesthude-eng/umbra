package com.umbra.app.ui

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.umbra.app.data.media.Attachments
import com.umbra.app.data.msg.voiceDurationText
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.data.repo.UiAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Длинная сторона распакованного кадра: больше экрана телефона не нужно. */
private const val VIEWER_MAX_PX = 2048

/**
 * Просмотр вложения внутри Umbra: фото с увеличением, видео с паузой
 * и перемоткой. Раньше чат всегда отдавал файл стороннему приложению,
 * поэтому внешний просмотр оставлен запасным вариантом.
 */
@Composable
fun AttachmentViewerDialog(
    repo: ChatRepository,
    attachment: UiAttachment,
    onDismiss: () -> Unit,
    onOpenExternally: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val key = attachment.mediaId ?: attachment.localPath ?: attachment.name
        var file by remember(key) { mutableStateOf<File?>(null) }
        var problem by remember(key) { mutableStateOf<String?>(null) }
        var attempt by remember(key) { mutableIntStateOf(0) }
        // Файла может ещё не быть на телефоне — репозиторий скачает и расшифрует его.
        LaunchedEffect(key, attempt) {
            problem = null
            file = null
            file = try {
                repo.attachmentLocalFile(attachment)
            } catch (e: Exception) {
                problem = e.userMessage()
                null
            }
        }
        Surface(Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.94f)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onDismiss) { Icon(Icons.Filled.Close, "Закрыть", tint = Color.White) }
                    Text(
                        attachment.name,
                        Modifier.weight(1f),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    IconButton(onShare) { Icon(Icons.Filled.Share, "Поделиться", tint = Color.White) }
                    IconButton(onSave) { Icon(Icons.Filled.Download, "Сохранить в файлы", tint = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val ready = file
                    val failure = problem
                    when {
                        failure != null -> ViewerProblem(failure) { attempt++ }
                        ready == null -> CircularProgressIndicator(color = Color.White)
                        attachment.isVideo -> VideoViewer(ready, attachment) { problem = it }
                        else -> ImageViewer(ready) { problem = it }
                    }
                }
                TextButton(
                    onOpenExternally,
                    Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                ) { Text("Открыть во внешнем приложении", color = Color.White) }
            }
        }
    }
}

/** Сообщение вместо бесконечного индикатора: с явной повторной попыткой. */
@Composable
private fun ViewerProblem(message: String, onRetry: () -> Unit) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        TextButton(onRetry) { Text("Повторить", color = Color.White) }
    }
}

/** Фото: щипок для увеличения, перетаскивание и двойное касание для сброса. */
@Composable
private fun ImageViewer(file: File, onProblem: (String) -> Unit) {
    var bitmap by remember(file.path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.path) {
        // Распаковка картинки тяжёлая — делаем её вне потока отрисовки.
        val decoded = withContext(Dispatchers.IO) {
            runCatching { Attachments.thumbnail(file, "image", VIEWER_MAX_PX) }.getOrNull()
        }
        if (decoded == null) onProblem("Не удалось открыть изображение.") else bitmap = decoded
    }
    val image = bitmap
    if (image == null) {
        CircularProgressIndicator(color = Color.White)
        return
    }
    var scale by remember(file.path) { mutableFloatStateOf(1f) }
    var offset by remember(file.path) { mutableStateOf(Offset.Zero) }
    Image(
        image.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(file.path) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    // На исходном масштабе кадр снова по центру.
                    offset = if (scale <= 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(file.path) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                })
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}

/** Видео: воспроизведение внутри чата с паузой, перемоткой и разбором ошибок. */
@Composable
private fun VideoViewer(file: File, attachment: UiAttachment, onProblem: (String) -> Unit) {
    val player = remember(file.path) { MediaPlayer() }
    var prepared by remember(file.path) { mutableStateOf(false) }
    var playing by remember(file.path) { mutableStateOf(false) }
    var duration by remember(file.path) { mutableIntStateOf(0) }
    var position by remember(file.path) { mutableIntStateOf(0) }
    var scrubbing by remember(file.path) { mutableStateOf(false) }
    DisposableEffect(file.path) {
        onDispose {
            // Плеер держит файл и звук: снимаем всё при закрытии просмотра.
            runCatching { player.setOnPreparedListener(null) }
            runCatching { player.setOnCompletionListener(null) }
            runCatching { player.setOnErrorListener(null) }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
    }
    // Ползунок двигается, пока идёт воспроизведение и палец его не держит.
    LaunchedEffect(playing, prepared) {
        while (playing && prepared) {
            if (!scrubbing) position = runCatching { player.currentPosition }.getOrDefault(position)
            delay(200)
        }
    }
    val ratio = if (attachment.width > 0 && attachment.height > 0) {
        (attachment.width.toFloat() / attachment.height.toFloat()).coerceIn(0.4f, 2.5f)
    } else {
        16f / 9f
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            runCatching {
                                player.reset()
                                player.setDisplay(holder)
                                player.setDataSource(file.absolutePath)
                                player.setOnPreparedListener { mp ->
                                    duration = mp.duration.coerceAtLeast(0)
                                    prepared = true
                                    runCatching { mp.start() }.onSuccess { playing = true }
                                }
                                player.setOnCompletionListener {
                                    playing = false
                                    position = duration
                                }
                                player.setOnErrorListener { _, _, _ ->
                                    prepared = false
                                    playing = false
                                    onProblem("Не удалось воспроизвести видео.")
                                    true
                                }
                                player.prepareAsync()
                            }.onFailure { onProblem("Не удалось воспроизвести видео.") }
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            // Сворачивание окна: сначала пауза, потом отвязка от поверхности.
                            runCatching { if (player.isPlaying) player.pause() }
                            playing = false
                            runCatching { player.setDisplay(null) }
                        }
                    })
                }
            },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                enabled = prepared,
                onClick = {
                    if (playing) {
                        runCatching { player.pause() }
                        playing = false
                    } else {
                        runCatching { player.start() }.onSuccess { playing = true }
                    }
                },
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (playing) "Пауза" else "Воспроизвести",
                    tint = Color.White,
                )
            }
            val total = duration.toFloat().coerceAtLeast(1f)
            Slider(
                value = position.toFloat().coerceIn(0f, total),
                onValueChange = {
                    scrubbing = true
                    position = it.toInt()
                },
                onValueChangeFinished = {
                    runCatching { player.seekTo(position) }
                    scrubbing = false
                },
                valueRange = 0f..total,
                enabled = prepared && duration > 0,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${voiceDurationText(position.toLong())} / ${voiceDurationText(duration.toLong())}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
