package com.umbra.app.ui

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.umbra.app.data.media.Attachments
import com.umbra.app.data.msg.voiceDurationText
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.data.repo.UiAttachment
import com.umbra.app.ui.theme.LocalUmbraReducedMotion
import com.umbra.app.ui.theme.LocalUmbraMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/** Длинная сторона распакованного кадра: больше экрана телефона не нужно. */
private const val VIEWER_MAX_PX = 2048

/**
 * Просмотр вложения внутри Umbra: фото с увеличением, видео с паузой
 * и перемоткой. Раньше чат всегда отдавал файл стороннему приложению,
 * поэтому внешний просмотр оставлен запасным вариантом.
 */
@Composable
fun AttachmentViewerOverlay(
    repo: ChatRepository,
    attachment: UiAttachment,
    sourceBounds: Rect?,
    onDismiss: () -> Unit,
    onOpenExternally: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    val reducedMotion = LocalUmbraReducedMotion.current
    val motion = LocalUmbraMotion.current
    var appeared by remember(attachment.mediaId, attachment.localPath) { mutableStateOf(reducedMotion) }
    var closing by remember(attachment.mediaId, attachment.localPath) { mutableStateOf(false) }
    var dragY by remember(attachment.mediaId, attachment.localPath) { mutableFloatStateOf(0f) }
    var predictiveProgress by remember(attachment.mediaId, attachment.localPath) { mutableFloatStateOf(0f) }
    var contentZoomed by remember(attachment.mediaId, attachment.localPath) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val transitionProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(if (reducedMotion) 0 else motion.standardMs, easing = FastOutSlowInEasing),
        label = "media-source-transition",
    )
    val visualProgress = (transitionProgress * (1f - predictiveProgress)).coerceIn(0f, 1f)

    fun closeViewer() {
        if (closing) return
        if (reducedMotion) onDismiss() else {
            closing = true
            appeared = false
            scope.launch { delay(motion.standardMs.toLong()); onDismiss() }
        }
    }

    LaunchedEffect(Unit) { appeared = true }
    PredictiveBackHandler(enabled = !closing) { events ->
        try {
            events.collect { event ->
                predictiveProgress = if (reducedMotion) 0f else event.progress.coerceIn(0f, 1f)
            }
            closeViewer()
        } catch (_: CancellationException) {
            val start = predictiveProgress
            Animatable(start).animateTo(
                0f,
                spring(dampingRatio = motion.springDamping, stiffness = motion.springStiffness),
            ) { predictiveProgress = value }
        }
    }

    val key = attachment.mediaId ?: attachment.localPath ?: attachment.name
    var file by remember(key) { mutableStateOf<File?>(null) }
    var problem by remember(key) { mutableStateOf<String?>(null) }
    var attempt by remember(key) { mutableIntStateOf(0) }
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

    Surface(
        modifier = Modifier.fillMaxSize().zIndex(50f).graphicsLayer {
            val p = visualProgress
            val source = sourceBounds
            val sourceScale = if (source != null && size.width > 0f && size.height > 0f) {
                minOf(source.width / size.width, source.height / size.height).coerceIn(0.12f, 0.92f)
            } else 0.96f
            val transitionScale = sourceScale + (1f - sourceScale) * p
            val distance = abs(dragY)
            val dragScale = (1f - distance / 2600f).coerceIn(0.90f, 1f)
            scaleX = transitionScale * dragScale
            scaleY = transitionScale * dragScale
            translationX = if (source != null) (source.center.x - size.width / 2f) * (1f - p) else 0f
            translationY = (if (source != null) (source.center.y - size.height / 2f) * (1f - p) else 0f) + dragY
            alpha = p * (1f - distance / 900f).coerceIn(0.45f, 1f)
        }.pointerInput(closing, contentZoomed) {
            val tracker = VelocityTracker()
            if (!closing && !contentZoomed) detectVerticalDragGestures(
                onDragStart = { tracker.resetTracking() },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    dragY = (dragY + amount).coerceIn(-size.height * 0.9f, size.height * 0.9f)
                },
                onDragEnd = {
                    val velocity = tracker.calculateVelocity().y
                    if (abs(dragY) > 150.dp.toPx() || abs(velocity) >= motion.dismissVelocity) {
                        closeViewer()
                    } else {
                        val start = dragY
                        scope.launch {
                            Animatable(start).animateTo(
                                0f,
                                spring(dampingRatio = motion.springDamping, stiffness = motion.springStiffness),
                            ) { dragY = value }
                        }
                    }
                },
                onDragCancel = {
                    val start = dragY
                    scope.launch { Animatable(start).animateTo(0f) { dragY = value } }
                },
            )
        },
        shape = RoundedCornerShape((24f * (1f - visualProgress)).dp),
        color = Color.Black.copy(alpha = 0.94f),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().alienTopEdge().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ closeViewer() }) { Icon(Icons.Filled.Close, "Закрыть", tint = Color.White) }
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
                    ready == null -> MediaLoadingSkeleton()
                    attachment.isVideo -> VideoViewer(ready, attachment) { problem = it }
                    else -> ImageViewer(ready, onZoomChanged = { contentZoomed = it }) { problem = it }
                }
            }
            TextButton(
                onOpenExternally,
                Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
            ) { Text("Открыть во внешнем приложении", color = Color.White) }
        }
    }
}

/** Спокойный skeleton без layout-скачка; reduced motion оставляет статичное состояние. */
@Composable
private fun MediaLoadingSkeleton() {
    val reduced = LocalUmbraReducedMotion.current
    val shimmer = rememberInfiniteTransition(label = "media-skeleton")
    val alpha by shimmer.animateFloat(
        initialValue = 0.24f,
        targetValue = if (reduced) 0.24f else 0.62f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "media-skeleton-alpha",
    )
    Box(
        Modifier.width(220.dp).height(160.dp).clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = alpha)),
    )
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
private fun ImageViewer(file: File, onZoomChanged: (Boolean) -> Unit, onProblem: (String) -> Unit) {
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
    LaunchedEffect(scale) { onZoomChanged(scale > 1.01f) }
    DisposableEffect(file.path) { onDispose { onZoomChanged(false) } }
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
                    if (scale <= 1f) offset = Offset.Zero else {
                        val limitX = size.width * (scale - 1f) / 2f
                        val limitY = size.height * (scale - 1f) / 2f
                        val moved = offset + pan
                        offset = Offset(
                            moved.x.coerceIn(-limitX, limitX),
                            moved.y.coerceIn(-limitY, limitY),
                        )
                    }
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
