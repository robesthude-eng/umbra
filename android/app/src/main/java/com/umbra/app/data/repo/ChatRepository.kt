package com.umbra.app.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import com.umbra.app.crypto.CryptoManager
import com.umbra.app.data.api.ChallengeRequest
import com.umbra.app.data.api.MessageDto
import com.umbra.app.data.api.SendChatMessageRequest
import com.umbra.app.data.api.SendMessageRequest
import com.umbra.app.data.api.UmbraApi
import com.umbra.app.data.api.VerifyRequest
import com.umbra.app.data.db.AppDatabase
import com.umbra.app.data.db.ChatEntity
import com.umbra.app.data.db.MessageEntity
import com.umbra.app.data.media.MediaInfo
import com.umbra.app.data.media.MessagePayload
import com.umbra.app.data.ws.WebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/** UI-модель сообщения (с уже расшифрованным содержимым; в БД его нет). */
data class UiMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val createdAt: String,
    val expiresAt: String?,
    val outgoing: Boolean,
    /** Метаданные вложения из E2E-конверта (ключ, nonce, имя — сервер их не видит). */
    val media: MediaInfo? = null,
)

/**
 * Единая точка доступа к данным: объединяет API, Room, WebSocket и криптографию.
 *
 * Приватность: в Room — ТОЛЬКО ciphertext. Открытый текст существует лишь в памяти:
 *  - входящие расшифровываются «на лету» при чтении;
 *  - собственные отправленные хранятся во временном кэше [sentCache] (не в БД).
 *
 * Медиа: файлы шифруются AES-256-GCM (ключ/nonce — внутри E2E-конверта сообщения),
 * на сервер уходит opaque ciphertext c generic-именем и «application/octet-stream».
 * Расшифрованные файлы кэшируются в приватной директории приложения
 * (cacheDir/media) и удаляются при выходе; в Room и в общее хранилище не попадают.
 */
class ChatRepository(
    private val context: Context,
    private val api: UmbraApi,
    private val db: AppDatabase,
    private val crypto: CryptoManager,
    private val ws: WebSocketClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var eventJob: Job? = null

    /** Открытый текст собственных отправленных сообщений (только в памяти). */
    private val sentCache = mutableMapOf<String, String>()

    private fun auth() = "Bearer ${requireNotNull(crypto.currentToken())}"

    // ---------- аутентификация / регистрация ----------

    suspend fun register(username: String) {
        crypto.generateAndPersistIdentity()
        val resp = api.register(crypto.buildRegisterRequest(username))
        crypto.saveUser(username, resp.id)
    }

    suspend fun login(username: String) {
        val challenge = api.challenge(ChallengeRequest(username)).challenge
        val signature = crypto.signChallenge(challenge)
        val verify = api.verify(VerifyRequest(username, challenge, signature))
        crypto.saveSession(verify.token)
        val acct = api.account("Bearer ${verify.token}")
        crypto.saveUser(acct.username, acct.id)
    }

    fun isLoggedIn(): Boolean = crypto.currentToken() != null

    fun logout() {
        disconnectRealtime()
        crypto.clearSession()
        sentCache.clear()
        // Расшифрованный кэш медиа не переживает выход.
        runCatching { File(context.cacheDir, MEDIA_CACHE_DIR).deleteRecursively() }
    }

    // ---------- realtime (WebSocket) ----------

    /** Подключается к WS и начинает обработку push-событий (входящие сообщения, звонки). */
    fun connectRealtime() {
        val token = crypto.currentToken() ?: return
        ws.connect(token)
        eventJob?.cancel()
        eventJob = scope.launch {
            ws.eventFlow.collect { event ->
                when (event.type) {
                    "message" -> runCatching {
                        val dto = json.decodeFromJsonElement<MessageDto>(event.data)
                        persist(dto)
                    }
                    // "call" и прочие события звонков обрабатываются на этапе звонков.
                    else -> Unit
                }
            }
        }
    }

    fun disconnectRealtime() {
        eventJob?.cancel()
        eventJob = null
        ws.disconnect()
    }

    // ---------- чаты ----------

    /** Начинает диалог с пользователем: берёт prekeys, устанавливает E2E-сессию, создаёт чат. */
    suspend fun startChat(username: String): ChatEntity {
        val bundle = api.prekeys(username)
        crypto.establishSession(bundle.id, bundle)
        val chat = ChatEntity(id = bundle.id, type = "dm", title = bundle.username)
        db.chatDao().upsert(chat)
        return chat
    }

    fun chats(): Flow<List<ChatEntity>> = db.chatDao().all()

    suspend fun refreshChats() {
        api.chats(auth()).chats.forEach { c ->
            db.chatDao().upsert(ChatEntity(c.id, c.type, c.title))
        }
    }

    // ---------- сообщения ----------

    fun messagesFor(chatId: String): Flow<List<UiMessage>> =
        db.messageDao().messagesFor(chatId).map { list ->
            list.map { e ->
                val outgoing = e.senderId == crypto.userId()
                val plain: String? = if (outgoing) {
                    sentCache[e.id]
                } else {
                    runCatching { crypto.decrypt(e.senderId, e.ciphertext) }.getOrNull()
                }
                if (plain == null) {
                    UiMessage(
                        id = e.id,
                        senderId = e.senderId,
                        text = if (outgoing) "[сообщение недоступно после перезапуска]" else "[не удалось расшифровать]",
                        createdAt = e.createdAt,
                        expiresAt = e.expiresAt,
                        outgoing = outgoing,
                    )
                } else {
                    val payload = parsePayload(plain)
                    UiMessage(
                        id = e.id,
                        senderId = e.senderId,
                        text = payload.text,
                        createdAt = e.createdAt,
                        expiresAt = e.expiresAt,
                        outgoing = outgoing,
                        media = payload.media,
                    )
                }
            }
        }

    suspend fun sendMessage(recipientId: String, plaintext: String, expiresIn: Long? = null) {
        val ciphertext = crypto.encrypt(recipientId, plaintext)
        val dto = api.sendMessage(auth(), SendMessageRequest(recipientId, ciphertext, expiresIn))
        sentCache[dto.id] = plaintext
        persist(dto)
    }

    suspend fun sendChatMessage(chatId: String, plaintext: String, expiresIn: Long? = null) {
        // Групповое шифрование (Sender Keys/MLS) — отдельный этап; в MVP — личный ciphertext.
        val ciphertext = crypto.encrypt(chatId, plaintext)
        val dto = api.sendChatMessage(
            auth(), chatId,
            SendChatMessageRequest(ciphertext = ciphertext, expires_in = expiresIn),
        )
        sentCache[dto.id] = plaintext
        persist(dto)
    }

    // ---------- медиа ----------

    /**
     * Отправляет файл как E2E-медиа: читает Uri (SAF), шифрует AES-256-GCM,
     * загружает ciphertext на сервер и отправляет сообщение с E2E-конвертом
     * (ключ, nonce, настоящее имя и MIME — внутри, сервер их не видит).
     *
     * @param chatId диалог (для dm — id получателя)
     * @param uri документ/фото из системного пикера
     * @param caption необязательная подпись
     */
    suspend fun sendMedia(chatId: String, uri: Uri, caption: String = "", expiresIn: Long? = null) {
        val resolver = context.contentResolver
        val displayName: String? = resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val plain = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("не удалось прочитать файл")
        }
        if (plain.isEmpty()) throw IOException("пустой файл")
        if (plain.size.toLong() > MAX_MEDIA_BYTES) {
            throw IOException("файл больше ${MAX_MEDIA_BYTES / MIB} MiB (лимит сервера)")
        }

        val key = crypto.newFileKey()
        val nonce = crypto.newFileNonce()
        val ciphertext = crypto.encryptFileBytes(key, nonce, plain)

        // Серверу всё непрозрачно: generic-имя и generic-MIME, без утечки метаданных.
        val part = MultipartBody.Part.createFormData(
            "file", "blob.bin",
            ciphertext.toRequestBody("application/octet-stream".toMediaType()),
        )
        val contentTypeField = "application/octet-stream".toRequestBody("text/plain".toMediaType())
        val upload = api.uploadMedia(auth(), part, contentTypeField)
        if (!upload.isSuccessful) throw IOException("загрузка медиа: HTTP ${upload.code()}")
        val mediaId = upload.body()?.id ?: throw IOException("загрузка медиа: пустой ответ")

        val payload = MessagePayload(
            text = caption,
            media = MediaInfo(
                id = mediaId,
                kind = if (mime.startsWith("image/")) MediaInfo.KIND_PHOTO else MediaInfo.KIND_FILE,
                contentType = mime,
                size = plain.size.toLong(),
                name = displayName,
                key = b64(key),
                nonce = b64(nonce),
            ),
        )
        val envelope = json.encodeToString(payload)
        val messageCiphertext = crypto.encrypt(chatId, envelope)
        val dto = api.sendMessage(auth(), SendMessageRequest(chatId, messageCiphertext, expiresIn))
        sentCache[dto.id] = envelope
        persist(dto)
    }

    /**
     * Скачивает ciphertext медиа с сервера и расшифровывает в приватный кэш
     * приложения (cacheDir/media). Повторный вызов для того же медиа отдаёт
     * готовый файл без сети. Кэш удаляется при logout().
     */
    suspend fun fetchMediaFile(info: MediaInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, MEDIA_CACHE_DIR).apply { mkdirs() }
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(info.contentType) ?: "bin"
        val out = File(dir, "${info.id}.$ext")
        if (out.isFile && out.length() == info.size) return@withContext out

        val response = api.downloadMedia(auth(), info.id)
        if (!response.isSuccessful) throw IOException("скачивание медиа: HTTP ${response.code()}")
        val body = response.body() ?: throw IOException("скачивание медиа: пустой ответ")
        val ciphertext = body.use { it.bytes() }
        val plain = crypto.decryptFileBytes(fromB64(info.key), fromB64(info.nonce), ciphertext)
        if (plain.size.toLong() != info.size) {
            throw IOException("размер расшифрованного файла не совпадает с конвертом")
        }
        // Атомарная публикация: читатель не увидит частичный файл.
        val tmp = File(dir, out.name + ".tmp")
        tmp.writeBytes(plain)
        if (!tmp.renameTo(out)) {
            out.writeBytes(plain)
            tmp.delete()
        }
        out
    }

    // ---------- вспомогательное ----------

    /**
     * Разбирает открытый текст сообщения: JSON-конверт [MessagePayload] (медиа)
     * или сырая строка (обычное текстовое сообщение, обратная совместимость).
     */
    private fun parsePayload(plain: String): MessagePayload {
        if (!plain.startsWith("{")) return MessagePayload(text = plain)
        return runCatching { json.decodeFromString<MessagePayload>(plain) }
            .getOrElse { MessagePayload(text = plain) }
    }

    private suspend fun persist(dto: MessageDto) {
        db.messageDao().upsert(
            MessageEntity(
                id = dto.id,
                senderId = dto.sender_id,
                recipientId = dto.recipient_id,
                chatId = dto.chat_id.ifEmpty { dto.recipient_id },
                ciphertext = dto.ciphertext,
                createdAt = dto.created_at,
                expiresAt = dto.expires_at,
            )
        )
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun fromB64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        /** Приватный кэш расшифрованных медиа внутри cacheDir приложения. */
        const val MEDIA_CACHE_DIR = "media"

        /** Лимит сервера MAX_MEDIA_BYTES по умолчанию (50 MiB) для открытого файла. */
        const val MAX_MEDIA_BYTES = 50L * 1024 * 1024
        private const val MIB = 1024 * 1024
    }
}
