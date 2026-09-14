package com.umbra.app.data.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effects
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Пережатие видео до 720p перед отправкой (media3 Transformer, аппаратный
 * кодек телефона). Файл никогда не теряется: любая ошибка или рост размера
 * означают отправку оригинала.
 */
@OptIn(UnstableApi::class)
object VideoCompressor {
    /** Короткая сторона кадра после пережатия. */
    const val SHORT_SIDE_PX = 720

    /** Мелкие ролики жмём впустую — проще отправить как есть. */
    private const val MIN_BYTES = 2L * 1024L * 1024L

    /**
     * @param onProgress доля готовности 0..100; вызывается не чаще раза в 200 мс.
     */
    suspend fun compress(
        context: Context,
        picked: PickedAttachment,
        onProgress: (Int) -> Unit = {},
    ): CompressionResult {
        val source = picked.file
        if (source.length() <= MIN_BYTES) return CompressionResult(picked, picked.size, false)
        val target = File(source.parentFile, UUID.randomUUID().toString() + ".mp4")
        val ok = try {
            transcode(context, source, target, onProgress)
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (_: Exception) {
            false
        }
        val size = if (ok) target.length() else 0L
        if (!ok || size <= 0L || size >= source.length()) {
            target.delete()
            return CompressionResult(picked, picked.size, false)
        }
        val originalSize = picked.size
        val duration = MediaCompressor.videoDuration(target).takeIf { it > 0 } ?: picked.durationMs
        source.delete()
        return CompressionResult(
            picked.copy(
                file = target,
                name = mp4Name(picked.name),
                mime = "video/mp4",
                size = size,
                durationMs = duration,
            ),
            originalSize,
            true,
        )
    }

    private suspend fun transcode(
        context: Context,
        source: File,
        target: File,
        onProgress: (Int) -> Unit,
    ): Boolean = coroutineScope {
        // Transformer требует Looper: создаём и опрашиваем его с главного потока.
        withContext(Dispatchers.Main) {
            val holder = ProgressHolder()
            var transformer: Transformer? = null
            val poll = launch {
                while (isActive) {
                    val state = transformer?.getProgress(holder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress.coerceIn(0, 100))
                    delay(200)
                }
            }
            try {
                suspendCancellableCoroutine { cont ->
                    val built = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, result: ExportResult) {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onError(
                                composition: Composition,
                                result: ExportResult,
                                exception: ExportException,
                            ) {
                                if (cont.isActive) cont.resume(false)
                            }
                        })
                        .build()
                    transformer = built
                    val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                        .setEffects(
                            Effects(
                                emptyList(),
                                listOf(Presentation.createForShortSide(SHORT_SIDE_PX)),
                            ),
                        )
                        .build()
                    cont.invokeOnCancellation {
                        Handler(Looper.getMainLooper()).post { runCatching { built.cancel() } }
                    }
                    built.start(item, target.absolutePath)
                }
            } finally {
                poll.cancel()
            }
        }
    }

    private fun mp4Name(name: String): String {
        val base = name.substringBeforeLast('.', name).ifBlank { "video" }
        return "$base.mp4"
    }
}
