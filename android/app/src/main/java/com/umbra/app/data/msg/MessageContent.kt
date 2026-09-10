package com.umbra.app.data.msg

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Модель T1: сервер хранит сообщения, клиент шлёт их «конвертом» — маленьким
 * JSON — внутри поля ciphertext (сервер не расшифровывает и не читает содержимое,
 * для него это непрозрачный base64-блоб, как и раньше; поле названо ciphertext
 * исторически). Приватность: «доверяй серверу» (TLS + владелец сервера).
 *
 * Поле ciphertext в API: base64(utf8(JSON)). Сервер делает base64-decode/encode,
 * поэтому полученный base64 идентичен отправленному.
 */
@Serializable
data class MessageContent(
    val v: Int = 1,
    val kind: String = KIND_TEXT,
    val text: String = "",
    val media: MediaContent? = null,
) {
    companion object {
        const val KIND_TEXT = "text"
        const val KIND_MEDIA = "media"

        /**
         * Голосовое сообщение: `media.id` — запись, загруженная в `/v1/media`,
         * `media.durationMs` — длительность, `media.mime` — `audio/mp4`.
         *
         * Клиенты 0.4.2 и старше этого kind не знают и покажут сообщение как
         * неподдерживаемое: у них нет ни этой ветки, ни проигрывателя.
         */
        const val KIND_VOICE = "voice"

        /**
         * Вложения: `media.id` — файл в `/v1/media`, `media.name` — имя,
         * `media.mime` — тип, `media.size` — размер. У фото и видео заполнены
         * `media.width` и `media.height`, у видео — ещё `media.durationMs`.
         *
         * Клиенты 0.8.0 и старше этих kind не знают и покажут сообщение как
         * неподдерживаемое: ни просмотра, ни скачивания у них нет.
         */
        const val KIND_IMAGE = "image"
        const val KIND_VIDEO = "video"
        const val KIND_FILE = "file"
    }
}

@Serializable
data class MediaContent(
    val id: String,
    val mime: String = "application/octet-stream",
    val size: Long = 0,
    val name: String? = null,
    /** Длительность звука или видео в миллисекундах; 0 — неизвестна. */
    val durationMs: Long = 0,
    /** Размеры фото или видео в точках; 0 — неизвестны. */
    val width: Int = 0,
    val height: Int = 0,
) {
    companion object {
        const val KIND_PHOTO = "photo"
        const val KIND_FILE = "file"
    }
}

/** Модель вложения для UI. */
data class UiMedia(val id: String, val mime: String, val size: Long, val name: String?)

/** 7_000 -> «0:07», 95_000 -> «1:35». */
fun voiceDurationText(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) + 500) / 1000
    return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
}

/** Голосовая часть конверта; null — сообщение не голосовое. */
fun MessageContent.voice(): MediaContent? =
    media?.takeIf { kind == MessageContent.KIND_VOICE && it.id.isNotBlank() }

/** Типы конвертов, которые показываются как вложение. */
val ATTACHMENT_KINDS: Set<String> = setOf(
    MessageContent.KIND_IMAGE,
    MessageContent.KIND_VIDEO,
    MessageContent.KIND_FILE,
)

/** Вложение конверта (фото, видео, файл); null — вложения нет. */
fun MessageContent.attachment(): MediaContent? =
    media?.takeIf { kind in ATTACHMENT_KINDS && it.id.isNotBlank() }

object MessageCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(content: MessageContent): String {
        val raw = json.encodeToString(content)
        return Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    fun decode(ciphertextB64: String): MessageContent? = runCatching {
        val bytes = Base64.decode(ciphertextB64, Base64.NO_WRAP)
        json.decodeFromString<MessageContent>(String(bytes, Charsets.UTF_8))
    }.getOrNull()

    /** Голосовая часть конверта или null, если это не голосовое сообщение. */
    fun voice(ciphertextB64: String): MediaContent? = decode(ciphertextB64)?.voice()

    /** Тип вложения и его описание или null, если вложения нет. */
    fun attachment(ciphertextB64: String): Pair<String, MediaContent>? =
        decode(ciphertextB64)?.let { c -> c.attachment()?.let { Pair(c.kind, it) } }

    /** Подпись вложения для списка чатов и для пузыря. */
    fun attachmentLabel(kind: String, media: MediaContent): String = when (kind) {
        MessageContent.KIND_IMAGE -> "Фото"
        MessageContent.KIND_VIDEO ->
            if (media.durationMs > 0) "Видео · " + voiceDurationText(media.durationMs) else "Видео"
        else -> media.name?.takeIf { it.isNotBlank() } ?: "Файл"
    }

    /** Подпись голосового сообщения для списка чатов и для пузыря. */
    fun voiceLabel(durationMs: Long): String =
        if (durationMs > 0) "Голосовое сообщение · ${voiceDurationText(durationMs)}" else "Голосовое сообщение"

    /** Показывает сообщение: текст конверта (медиа-подпись). */
    fun plainText(ciphertextB64: String): String {
        val c = decode(ciphertextB64) ?: return "Сообщение из другой версии приложения: содержимое недоступно"
        if (c.v != 1) return "Обновите приложение, чтобы прочитать это сообщение"
        c.voice()?.let { return voiceLabel(it.durationMs) }
        c.attachment()?.let { return attachmentLabel(c.kind, it) }
        if (c.kind == MessageContent.KIND_MEDIA) {
            return listOf(c.text, c.media?.name?.let { "Вложение: $it" } ?: "Вложение")
                .filter { it.isNotBlank() }.joinToString("\n") + "\nПросмотр вложений в этой версии пока недоступен."
        }
        if (c.kind != MessageContent.KIND_TEXT) return "Этот тип сообщения пока не поддерживается"
        return c.text.ifBlank { "Пустое сообщение" }
    }
}
