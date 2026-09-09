package com.umbra.app.data.msg

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    }
}

@Serializable
data class MediaContent(
    val id: String,
    val mime: String = "application/octet-stream",
    val size: Long = 0,
    val name: String? = null,
) {
    companion object {
        const val KIND_PHOTO = "photo"
        const val KIND_FILE = "file"
    }
}

/** Модель вложения для UI. */
data class UiMedia(val id: String, val mime: String, val size: Long, val name: String?)

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

    /** Показывает сообщение: текст конверта (медиа-подпись). */
    fun plainText(ciphertextB64: String): String {
        val c = decode(ciphertextB64) ?: return "Сообщение из другой версии приложения: содержимое недоступно"
        if (c.v != 1) return "Обновите приложение, чтобы прочитать это сообщение"
        if (c.kind == MessageContent.KIND_MEDIA) {
            return listOf(c.text, c.media?.name?.let { "Вложение: $it" } ?: "Вложение")
                .filter { it.isNotBlank() }.joinToString("\n") + "\nПросмотр вложений в этой версии пока недоступен."
        }
        if (c.kind != MessageContent.KIND_TEXT) return "Этот тип сообщения пока не поддерживается"
        return c.text.ifBlank { "Пустое сообщение" }
    }
}
