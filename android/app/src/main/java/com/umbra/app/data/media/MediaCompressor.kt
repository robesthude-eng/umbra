package com.umbra.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import com.umbra.app.data.msg.MessageContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Качество отправки медиа.
 *
 * - [COMPRESS] — фото уменьшается до [MediaCompressor.PHOTO_MAX_PX] и
 *   пережимается в JPEG, видео пережимается до 720p (см. [VideoCompressor]);
 * - [ORIGINAL] — файл уходит как есть, байт в байт (EXIF и метаданные тоже);
 * - [AUTO] — сжимать в мобильной сети, оригинал по Wi-Fi.
 *
 * Значение по умолчанию совпадает с прежним поведением приложения только для
 * файлов: фото и видео до 0.16.19 уходили всегда оригиналом.
 */
enum class MediaSendQuality { AUTO, COMPRESS, ORIGINAL }

/** Результат подготовки файла к отправке. */
data class CompressionResult(
    val attachment: PickedAttachment,
    /** Размер исходника; равен `attachment.size`, если сжатие не применялось. */
    val originalSize: Long,
    val compressed: Boolean,
)

/**
 * Сжатие фото перед отправкой.
 *
 * Отдельно от [Attachments], потому что сжатие — необязательный шаг: при
 * [MediaSendQuality.ORIGINAL] файл вообще не трогается. Ошибка сжатия никогда
 * не ломает отправку: в этом случае уходит оригинал.
 */
object MediaCompressor {
    /** Длинная сторона сжатого фото. */
    const val PHOTO_MAX_PX = 2048
    const val PHOTO_QUALITY = 82

    /** Мельче этого сжимать нечего: JPEG может даже вырасти. */
    private const val PHOTO_MIN_BYTES = 256L * 1024L

    /**
     * Готовит выбранный файл к отправке. Возвращает либо сжатую копию
     * (оригинал из очереди удаляется), либо исходное вложение без изменений.
     */
    suspend fun prepare(
        context: Context,
        picked: PickedAttachment,
        quality: MediaSendQuality,
        meteredNetwork: Boolean,
    ): CompressionResult {
        val compress = when (quality) {
            MediaSendQuality.ORIGINAL -> false
            MediaSendQuality.COMPRESS -> true
            MediaSendQuality.AUTO -> meteredNetwork
        }
        if (!compress) return CompressionResult(picked, picked.size, false)
        return when (picked.kind) {
            MessageContent.KIND_IMAGE -> compressPhoto(picked)
            MessageContent.KIND_VIDEO -> VideoCompressor.compress(context, picked)
            else -> CompressionResult(picked, picked.size, false)
        }
    }

    /** «2,4 МБ → 480 КБ» для подписи под переключателем качества. */
    fun savingText(originalSize: Long, size: Long): String =
        Attachments.sizeText(originalSize) + " → " + Attachments.sizeText(size)

    private suspend fun compressPhoto(picked: PickedAttachment): CompressionResult =
        withContext(Dispatchers.IO) {
            val source = picked.file
            if (source.length() <= PHOTO_MIN_BYTES) return@withContext CompressionResult(picked, picked.size, false)
            val target = File(source.parentFile, UUID.randomUUID().toString() + ".jpg")
            val done = runCatching { writeScaledJpeg(source, target) }.getOrDefault(false)
            val size = if (done) target.length() else 0L
            // Сжатие имеет смысл только если файл реально стал меньше.
            if (!done || size <= 0 || size >= source.length()) {
                target.delete()
                return@withContext CompressionResult(picked, picked.size, false)
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            val originalSize = picked.size
            source.delete()
            CompressionResult(
                picked.copy(
                    file = target,
                    name = jpegName(picked.name),
                    mime = "image/jpeg",
                    size = size,
                    width = bounds.outWidth.coerceAtLeast(0),
                    height = bounds.outHeight.coerceAtLeast(0),
                ),
                originalSize,
                true,
            )
        }

    /**
     * Уменьшает фото и пишет JPEG. Поворот из EXIF применяется к пикселям, а
     * сами теги (включая GPS-координаты съёмки) в копию не переносятся.
     */
    private fun writeScaledJpeg(source: File, target: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return false
        var sample = 1
        while (longest / (sample * 2) >= PHOTO_MAX_PX) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return false
        var bitmap = decoded
        try {
            val scale = PHOTO_MAX_PX.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat()
            if (scale < 1f) {
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                if (scaled != bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }
            val rotated = applyExifRotation(source, bitmap)
            if (rotated != bitmap) {
                bitmap.recycle()
                bitmap = rotated
            }
            target.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, out)) return false
            }
            return true
        } finally {
            bitmap.recycle()
        }
    }

    private fun applyExifRotation(source: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(source.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun jpegName(name: String): String {
        val base = name.substringBeforeLast('.', name).ifBlank { "photo" }
        return "$base.jpg"
    }

    /** Длительность видео нужна и после пережатия — читаем из готового файла. */
    internal fun videoDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }
}
