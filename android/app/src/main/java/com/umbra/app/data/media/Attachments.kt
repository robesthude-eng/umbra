package com.umbra.app.data.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.umbra.app.data.msg.MessageContent
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Вложение, выбранное человеком и уже скопированное в приватный каталог
 * приложения. Копия нужна потому, что выданный системой Uri живёт до
 * перезапуска, а отправка может ждать сеть сколько угодно долго.
 */
data class PickedAttachment(
    val file: File,
    val name: String,
    val mime: String,
    val size: Long,
    val kind: String,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
)

/** Работа с вложениями: копия, метаданные, миниатюры, открытие и сохранение. */
object Attachments {
    /** Максимальная сторона миниатюры в пузыре сообщения. */
    const val THUMB_MAX_PX = 1080
    const val DEFAULT_MIME = "application/octet-stream"

    fun kindFor(mime: String): String = when {
        mime.startsWith("image/") -> MessageContent.KIND_IMAGE
        mime.startsWith("video/") -> MessageContent.KIND_VIDEO
        else -> MessageContent.KIND_FILE
    }

    /** Фото и видео показываем картинкой, остальное — строкой с именем файла. */
    fun hasPreview(kind: String): Boolean =
        kind == MessageContent.KIND_IMAGE || kind == MessageContent.KIND_VIDEO

    /** «2,4 МБ», «860 КБ», «512 Б». */
    fun sizeText(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = 1024.0 * 1024.0
        return when {
            bytes >= mb -> String.format(Locale.getDefault(), "%.1f МБ", bytes / mb)
            bytes >= 1024 -> String.format(Locale.getDefault(), "%d КБ", bytes / 1024)
            else -> String.format(Locale.getDefault(), "%d Б", bytes)
        }
    }

    /**
     * Копирует выбранный файл в очередь отправки и считывает размеры и
     * длительность. Превышение лимита обрывает копирование, не дожидаясь конца
     * большого файла; недописанная копия удаляется.
     */
    fun copyToOutbox(context: Context, uri: Uri, dir: File, maxBytes: Long): PickedAttachment {
        val resolver = context.contentResolver
        val raw = rawName(context, uri)
        val mime = (resolver.getType(uri) ?: guessMime(raw)).ifBlank { DEFAULT_MIME }
        val kind = kindFor(mime)
        val name = fileName(raw, mime, kind)
        dir.mkdirs()
        val target = File(dir, UUID.randomUUID().toString() + suffixFor(name, mime))
        var total = 0L
        try {
            val input = resolver.openInputStream(uri)
                ?: throw IllegalStateException("Не удалось открыть выбранный файл. Выберите его заново.")
            input.use { source ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= maxBytes) {
                            "Файл больше " + sizeText(maxBytes) + ". Отправьте файл меньшего размера."
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        if (total <= 0) {
            target.delete()
            throw IllegalStateException("Файл пустой или недоступен. Выберите другой файл.")
        }
        var width = 0
        var height = 0
        var durationMs = 0L
        if (kind == MessageContent.KIND_IMAGE) {
            val bounds = imageBounds(target)
            width = bounds.first
            height = bounds.second
        } else if (kind == MessageContent.KIND_VIDEO) {
            val meta = videoMeta(target)
            width = meta.first
            height = meta.second
            durationMs = meta.third
        }
        return PickedAttachment(target, name, mime, total, kind, durationMs, width, height)
    }

    /** Миниатюра: для фото — уменьшенное изображение, для видео — первый кадр. */
    fun thumbnail(file: File, kind: String, maxPx: Int = THUMB_MAX_PX): Bitmap? = when (kind) {
        MessageContent.KIND_IMAGE -> decodeImage(file, maxPx)
        MessageContent.KIND_VIDEO -> videoFrame(file)
        else -> null
    }

    /** Временная ссылка на файл для других приложений (просмотр, отправка). */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)

    /** Открыть вложение системным приложением: галерея, плеер, читалка PDF. */
    fun openIntent(context: Context, file: File, mime: String): Intent {
        val target = Intent(Intent.ACTION_VIEW)
        target.setDataAndType(uriFor(context, file), mime.ifBlank { DEFAULT_MIME })
        target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(target, "Открыть")
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return chooser
    }

    /** Поделиться вложением в другом приложении. */
    fun shareIntent(context: Context, file: File, mime: String): Intent {
        val send = Intent(Intent.ACTION_SEND)
        send.type = mime.ifBlank { DEFAULT_MIME }
        send.putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, "Поделиться")
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return chooser
    }

    /**
     * Сохранение в выбранное человеком место. Системный выбор папки не требует
     * разрешения на память ни на одной версии Android.
     */
    fun writeTo(context: Context, destination: Uri, source: File) {
        val out = context.contentResolver.openOutputStream(destination)
            ?: throw IllegalStateException("Не удалось записать файл. Выберите другую папку.")
        out.use { target -> source.inputStream().use { it.copyTo(target) } }
    }

    private fun rawName(context: Context, uri: Uri): String? {
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
            }
        }
        return name ?: uri.lastPathSegment
    }

    /** Имя без разделителей пути и переводов строки; пустое — заменяем по типу. */
    private fun fileName(raw: String?, mime: String, kind: String): String {
        val cleaned = raw.orEmpty().map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' || ch == ' ' || ch == '(' || ch == ')') ch else '_'
        }.joinToString("").trim().trim('.')
        if (cleaned.isNotEmpty()) return cleaned.take(120)
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        val base = when (kind) {
            MessageContent.KIND_IMAGE -> "photo"
            MessageContent.KIND_VIDEO -> "video"
            else -> "file"
        }
        return if (extension.isNullOrBlank()) base else base + "." + extension
    }

    private fun suffixFor(name: String, mime: String): String {
        val fromName = name.substringAfterLast('.', "")
        if (fromName.isNotBlank() && fromName.length <= 8) return "." + fromName.lowercase(Locale.US)
        val fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        return if (fromMime.isNullOrBlank()) "" else "." + fromMime
    }

    private fun guessMime(name: String?): String {
        val extension = name?.substringAfterLast('.', "").orEmpty().lowercase(Locale.US)
        if (extension.isBlank()) return DEFAULT_MIME
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: DEFAULT_MIME
    }

    private fun imageBounds(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }
        return Pair(options.outWidth.coerceAtLeast(0), options.outHeight.coerceAtLeast(0))
    }

    /** Ширина, высота и длительность видео; поворот 90/270 меняет стороны местами. */
    private fun videoMeta(file: File): Triple<Int, Int, Long> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) Triple(height, width, duration) else Triple(width, height, duration)
        } catch (e: Exception) {
            Triple(0, 0, 0L)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeImage(file: File, maxPx: Int): Bitmap? {
        val bounds = imageBounds(file)
        val longest = maxOf(bounds.first, bounds.second)
        if (longest <= 0) return null
        var sample = 1
        while (maxPx > 0 && longest / (sample * 2) >= maxPx) sample *= 2
        val options = BitmapFactory.Options()
        options.inSampleSize = sample
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
    }

    private fun videoFrame(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.frameAtTime
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
