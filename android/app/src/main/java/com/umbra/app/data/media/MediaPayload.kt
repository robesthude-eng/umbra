package com.umbra.app.data.media

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * E2E-конверт сообщения с медиа.
 *
 * Открытый текст сообщения — JSON [MessagePayload], который сам шифруется
 * Signal-сессией (X3DH + Double Ratchet): сервер видит только ciphertext.
 * Текстовые сообщения без вложений по-прежнему шифруются как есть (сырая строка),
 * поэтому получатель пробует разобрать JSON и при неудаче считает сообщение текстом.
 *
 * Приватность: при загрузке на сервер клиент отправляет generic-имя файла
 * («blob.bin») и content_type «application/octet-stream». Настоящие MIME,
 * имя файла, размер открытого текста и ключи шифрования остаются ТОЛЬКО
 * внутри зашифрованного конверта — сервер их не видит (см. README, «Важно о приватности»).
 *
 * E2E-медиа вернулось в клиент v0.2.0 из прежней ветки v0.1 (см. CHANGES.md).
 */
@Serializable
data class MediaInfo(
    /** id медиа на сервере (медиа-блоб, загруженный через POST /v1/media). */
    val id: String,
    /** photo | file — как рисовать вложение в UI. */
    val kind: String,
    /** Настоящий MIME исходного файла (внутри конверта, сервер его не знает). */
    @SerialName("content_type")
    val contentType: String,
    /** Размер ОТКРЫТОГО файла в байтах (ciphertext больше на GCM-тег, 16 байт). */
    val size: Long,
    /** Исходное имя файла, если SAF его отдал. */
    val name: String? = null,
    /** Base64 AES-256 ключ файла (32 байта). */
    val key: String,
    /** Base64 GCM-nonce (12 байт), уникален для каждого файла. */
    val nonce: String,
) {
    companion object {
        const val KIND_PHOTO = "photo"
        const val KIND_FILE = "file"
    }
}

@Serializable
data class MessagePayload(
    /** Подпись к вложению или обычный текст сообщения. */
    val text: String = "",
    /** Вложение; null для чисто текстовых сообщений. */
    val media: MediaInfo? = null,
)
