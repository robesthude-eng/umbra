package com.umbra.app.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.room.withTransaction
import com.umbra.app.crypto.CryptoManager
import com.umbra.app.data.api.*
import com.umbra.app.data.db.*
import com.umbra.app.data.media.MediaInfo
import com.umbra.app.data.media.MessagePayload
import com.umbra.app.data.ws.WebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeFromString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

data class UiMessage(
    val id: String, val senderId: String, val text: String, val createdAt: String,
    val expiresAt: String?, val outgoing: Boolean, val deliveryState: String = "sent",
    val error: String? = null,
    /** Метаданные вложения из E2E-конверта (ключ, nonce, имя — сервер их не видит). */
    val media: MediaInfo? = null,
)

/** Room хранит ciphertext транспорта и отдельно локальную AEAD-копию истории. */
class ChatRepository(
    private val context: Context,
    private val api: UmbraApi,
    private val db: AppDatabase,
    private val crypto: CryptoManager,
    private val ws: WebSocketClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val syncMutex = Mutex()
    private val sendMutex = Mutex()
    private val authMutex = Mutex()
    private var eventJob: Job? = null
    private var pollJob: Job? = null
    private val logged = MutableStateFlow(crypto.currentToken() != null)
    val loggedIn = logged.asStateFlow()
    val connected = ws.connected
    private val syncProblem = MutableStateFlow<String?>(null)
    val syncError = syncProblem.asStateFlow()
    private var lastFullSyncMillis = 0L

    init {
        scope.launch {
            while (isActive) {
                try { db.messageDao().deleteExpired(System.currentTimeMillis()) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { syncProblem.value = "Не удалось обновить локальную историю" }
                delay(1000)
            }
        }
    }

    private fun auth() = "Bearer " + requireNotNull(crypto.currentToken()) { "Войдите в аккаунт" }
    fun isLoggedIn() = logged.value

    suspend fun register(username: String) = withContext(Dispatchers.IO) {
        check(crypto.userId() == null) { "На устройстве уже есть аккаунт. Используйте вход; ключи сохранены." }
        crypto.ensureIdentity()
        val request = db.withTransaction { crypto.buildRegisterRequest(username) }
        try {
            val registered = api.register(request)
            crypto.saveUser(registered.username, registered.id)
            db.withTransaction { crypto.markKeysPublished() }
        } catch (e: HttpException) {
            if (e.code() != 409) throw e
            // Регистрация могла завершиться на сервере до обрыва HTTP-ответа.
            try { login(username); return@withContext }
            catch (loginError: HttpException) {
                if (loginError.code() != 401) throw loginError
                error("Имя уже занято. Выберите другое имя; локальные ключи сохранены.")
            }
        }
        login(username)
    }

    suspend fun login(username: String) = withContext(Dispatchers.IO) {
        authMutex.withLock {
            check(crypto.hasIdentity()) { "На устройстве нет ключей этого аккаунта. Одного имени для входа недостаточно." }
            val saved = crypto.username()
            check(saved == null || saved == username) { "На устройстве сохранены ключи другого аккаунта" }
            val challenge = api.challenge(ChallengeRequest(username)).challenge
            val session = api.verify(VerifyRequest(username, challenge, crypto.signChallenge(challenge)))
            try {
                val account = api.account("Bearer " + session.token)
                crypto.saveUser(account.username, account.id)
                crypto.saveSession(session.token, session.expires_at)
                prepareAccount(account)
                logged.value = true
            } catch (e: Exception) {
                crypto.clearSession()
                logged.value = false
                throw e
            }
        }
    }

    private suspend fun prepareAccount(account: AccountResponse) {
        crypto.saveUser(account.username, account.id)
        adoptLegacyHistory(account.id)
        if (db.withTransaction { crypto.needsKeyPublication(account.key_version, account.one_time_prekey_count) }) {
            val request = db.withTransaction { crypto.buildRegisterRequest(account.username) }
            api.updateKeys(auth(), request)
            db.withTransaction { crypto.markKeysPublished() }
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val token = crypto.currentToken()
        disconnectRealtime()
        crypto.clearSession()
        logged.value = false
        // Локальный выход гарантирован даже без сети; серверный токен истечёт по TTL,
        // если отзыв сейчас доставить невозможно.
        if (token != null) {
            try { withTimeout(5000) { api.logout("Bearer " + token) } }
            catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e }
            catch (_: Exception) { }
        }
        // Расшифрованный кэш медиа не переживает выход.
        runCatching { File(context.cacheDir, MEDIA_CACHE_DIR).deleteRecursively() }
    }

    fun connectRealtime() {
        val token = crypto.currentToken() ?: return
        if (pollJob?.isActive == true) return
        eventJob = scope.launch {
            ws.eventFlow.collect { event ->
                try {
                    when (event.type) {
                        "connected" -> syncNow(forceFull = true)
                        "message" -> persist(json.decodeFromJsonElement<MessageDto>(event.data))
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    syncProblem.value = "Синхронизация не завершена. Повторим при восстановлении связи."
                    handleAuthFailure(e)
                }
            }
        }
        pollJob = scope.launch {
            var accountCheckedAt = 0L
            while (isActive && logged.value) {
                if (crypto.currentToken() == null) {
                    crypto.clearSession(); logged.value = false; ws.disconnect(); break
                }
                try {
                    if (accountCheckedAt == 0L || System.currentTimeMillis() - accountCheckedAt >= 300_000) {
                        prepareAccount(api.account(auth()))
                        accountCheckedAt = System.currentTimeMillis()
                        ws.connect(token)
                    }
                    syncNow(); flushOutbox(); syncProblem.value = null
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    syncProblem.value = "Синхронизация не завершена. Повторим при восстановлении связи."
                    handleAuthFailure(e)
                }
                delay(5000)
            }
        }
    }

    fun disconnectRealtime() {
        eventJob?.cancel(); eventJob = null
        pollJob?.cancel(); pollJob = null
        ws.disconnect()
    }

    suspend fun close() {
        disconnectRealtime()
        scope.coroutineContext[Job]?.cancelAndJoin()
        ws.close()
    }

    private fun handleAuthFailure(e: Exception) {
        if (e is HttpException && e.code() == 401) {
            crypto.clearSession()
            logged.value = false
            ws.disconnect()
        }
    }

    suspend fun startChat(username: String): ChatEntity = withContext(Dispatchers.IO) {
        val bundle = api.prekeys(username)
        require(bundle.id != crypto.userId()) { "Диалог с собой пока не поддерживается" }
        db.withTransaction {
            crypto.establishSession(bundle.id, bundle)
            ChatEntity(bundle.id, "dm", bundle.username).also { db.chatDao().upsert(it) }
        }
    }

    fun chats(): Flow<List<ChatEntity>> = db.chatDao().all()

    suspend fun refreshChats() = withContext(Dispatchers.IO) {
        api.chats(auth()).chats.forEach { db.chatDao().upsert(ChatEntity(it.id, it.type, it.title)) }
    }

    fun messagesFor(chatId: String): Flow<List<UiMessage>> =
        db.messageDao().messagesFor(crypto.userId().orEmpty(), chatId).map { list ->
            list.map { e ->
                val outgoing = e.senderId == e.ownerId
                // В localBody лежит AEAD-копия открытого текста: для медиа это JSON-конверт
                // MessagePayload, для текстовых — сырая строка (обратная совместимость).
                val plain = e.localBody?.let {
                    runCatching { crypto.openHistory(e.ownerId, e.id, it) }.getOrNull()
                }
                val payload = plain?.let(::parsePayload)
                val fallback = if (outgoing) "[старая локальная копия отсутствует]" else "[не удалось расшифровать]"
                UiMessage(
                    id = e.id,
                    senderId = e.senderId,
                    text = payload?.text ?: fallback,
                    createdAt = e.createdAt,
                    expiresAt = e.expiresAt,
                    outgoing = outgoing,
                    deliveryState = e.deliveryState,
                    error = e.error,
                    media = payload?.media,
                )
            }
        }.flowOn(Dispatchers.IO)

    /** Отправляет текстовое сообщение: шифруется ровно один раз, уходит через outbox. */
    suspend fun sendMessage(recipientId: String, plaintext: String, expiresIn: Long? = null) =
        enqueueMessage(recipientId, plaintext, expiresIn)

    /** Encrypt ровно один раз; outbox и новое состояние ratchet коммитятся вместе. */
    private suspend fun enqueueMessage(recipientId: String, plaintext: String, expiresIn: Long? = null) =
        withContext(Dispatchers.IO) {
            require(plaintext.isNotBlank() && plaintext.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Сообщение пустое или слишком длинное" }
            val ttl = expiresIn ?: 0
            require(ttl in 0..2_592_000) { "Некорректный срок сообщения" }
            val owner = requireNotNull(crypto.userId())
            check(logged.value) { "Войдите в аккаунт" }
            db.withTransaction {
                val chat = db.chatDao().get(recipientId) ?: error("Сначала создайте диалог")
                check(chat.type == "dm") { "Групповое E2E на Android ещё не реализовано. Отправка отключена." }
                val id = UUID.randomUUID().toString()
                val now = Instant.now()
                val expires = if (ttl > 0) now.plusSeconds(ttl) else null
                val ciphertext = crypto.encrypt(recipientId, plaintext)
                db.messageDao().upsert(MessageEntity(
                    id = "local:" + id, senderId = owner, recipientId = recipientId, chatId = recipientId,
                    ciphertext = ciphertext, createdAt = now.toString(), expiresAt = expires?.toString(),
                    ownerId = owner, localBody = crypto.sealHistory(owner, "local:" + id, plaintext),
                    createdAtMillis = now.toEpochMilli(), expiresAtMillis = expires?.toEpochMilli(),
                    deliveryState = "pending", clientId = id, expiresInSeconds = ttl,
                ))
            }
            scope.launch { flushOutbox() }
        }

    /**
     * Отправляет файл/фото как E2E-медиа: читает Uri (SAF/Photo Picker), шифрует файл
     * AES-256-GCM, загружает ciphertext на сервер и ставит сообщение в outbox с E2E-конвертом
     * (ключ, nonce, настоящее имя и MIME — внутри, сервер их не видит).
     */
    suspend fun sendMedia(chatId: String, uri: Uri, caption: String = "", expiresIn: Long? = null) =
        withContext(Dispatchers.IO) {
            check(logged.value) { "Войдите в аккаунт" }
            val resolver = context.contentResolver
            val displayName: String? = resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val plain = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("не удалось прочитать файл")
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
            enqueueMessage(chatId, envelope, expiresIn)
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

    suspend fun retry(id: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val message = db.messageDao().get(id) ?: return@withTransaction
            require(message.ownerId == crypto.userId() && message.senderId == message.ownerId && message.clientId != null)
            check(System.currentTimeMillis() - message.createdAtMillis < 29L * 86_400_000) {
                "Срок безопасного повтора истёк. Уточните у собеседника, получено ли сообщение."
            }
            if (message.expiresAtMillis?.let { it <= System.currentTimeMillis() } == true) {
                db.messageDao().delete(id); return@withTransaction
            }
            db.messageDao().upsert(message.copy(deliveryState = "pending", error = null))
        }
        flushOutbox()
    }

    private suspend fun flushOutbox() = sendMutex.withLock {
        if (!logged.value || crypto.currentToken() == null) return@withLock
        val owner = requireNotNull(crypto.userId())
        for (message in db.messageDao().pending(owner)) {
            if (System.currentTimeMillis() - message.createdAtMillis >= 29L * 86_400_000) {
                db.withTransaction {
                    db.messageDao().get(message.id)?.let {
                        db.messageDao().upsert(it.copy(deliveryState = "failed", error =
                            "Срок безопасного повтора истёк. Уточните доставку у собеседника."))
                    }
                }
                continue
            }
            if (message.expiresAtMillis?.let { it <= System.currentTimeMillis() } == true) {
                db.messageDao().delete(message.id); continue
            }
            try {
                val sent = api.sendMessage(auth(), SendMessageRequest(message.recipientId, message.ciphertext,
                    message.expiresInSeconds.takeIf { it > 0 }, message.clientId))
                require(sent.id.isNotEmpty() && !sent.id.startsWith("local:") && sent.chat_id.isEmpty() &&
                    sent.sender_id == owner && sent.recipient_id == message.recipientId &&
                    sent.ciphertext == message.ciphertext && sent.client_message_id == message.clientId) { "Некорректное подтверждение сервера" }
                db.withTransaction {
                    val current = db.messageDao().get(message.id) ?: return@withTransaction
                    val expires = sent.expires_at?.let(Instant::parse)
                    if (expires == null || expires.isAfter(Instant.now())) {
                        val text = crypto.openHistory(owner, current.id, requireNotNull(current.localBody))
                        db.messageDao().upsert(current.copy(id = sent.id, createdAt = sent.created_at,
                            createdAtMillis = Instant.parse(sent.created_at).toEpochMilli(),
                            expiresAt = sent.expires_at, expiresAtMillis = expires?.toEpochMilli(),
                            localBody = crypto.sealHistory(owner, sent.id, text), deliveryState = "sent", error = null))
                    }
                    db.messageDao().delete(message.id)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val retryable = e is IOException || e is HttpException && (e.code() >= 500 || e.code() == 429 || e.code() == 401)
                db.withTransaction {
                    db.messageDao().get(message.id)?.let {
                        db.messageDao().upsert(it.copy(deliveryState = if (retryable) "pending" else "failed",
                            error = if (retryable) "Ожидает связи с сервером" else "Не отправлено. Проверьте соединение и повторите."))
                    }
                }
                handleAuthFailure(e)
                if (retryable) break
            }
        }
    }

    private suspend fun syncNow(forceFull: Boolean = false) = syncMutex.withLock {
        val started = System.currentTimeMillis()
        val full = forceFull || lastFullSyncMillis == 0L || started - lastFullSyncMillis >= 900_000
        var newest = db.withTransaction {
            crypto.store().read("meta", "last_sync")?.let { Instant.parse(String(it, Charsets.UTF_8)) } ?: Instant.EPOCH
        }
        var since = if (full) Instant.EPOCH else newest.minusSeconds(30)
        var afterId: String? = null
        var pages = 0
        while (true) {
            val messages = api.messages(auth(), since.toString(), afterId, 200).messages
            for (dto in messages) {
                persist(dto)
                val timestamp = Instant.parse(dto.created_at)
                if (timestamp > newest) newest = timestamp
            }
            if (messages.size < 200) break
            val last = messages.last()
            val nextTime = Instant.parse(last.created_at)
            check(nextTime > since || nextTime == since && last.id != afterId) { "Курсор синхронизации не продвинулся" }
            since = nextTime
            afterId = last.id
            check(++pages <= 10000) { "Слишком большая история для одной синхронизации" }
        }
        // Курсор основан на времени сервера. Полная сверка также подбирает поздние коммиты.
        db.withTransaction { crypto.store().write("meta", "last_sync", newest.toString().toByteArray(Charsets.UTF_8)) }
        refreshChats()
        if (full) lastFullSyncMillis = started
    }

    private suspend fun persist(dto: MessageDto) {
        val owner = crypto.userId() ?: return
        require(dto.chat_id.isNotEmpty() || dto.recipient_id == owner) { "Сообщение адресовано другому аккаунту" }
        val chatId = dto.chat_id.ifEmpty { if (dto.sender_id == owner) dto.recipient_id else dto.sender_id }
        val expires = dto.expires_at?.let(Instant::parse)
        if (expires != null && !expires.isAfter(Instant.now())) { db.messageDao().delete(dto.id); return }
        val row = MessageEntity(dto.id, dto.sender_id, dto.recipient_id, chatId, dto.ciphertext,
            dto.created_at, dto.expires_at, ownerId = owner,
            createdAtMillis = Instant.parse(dto.created_at).toEpochMilli(), expiresAtMillis = expires?.toEpochMilli())
        try {
            db.withTransaction {
                val previous = db.messageDao().get(dto.id)
                if (previous != null) {
                    require(previous.ciphertext == dto.ciphertext && previous.senderId == dto.sender_id && previous.chatId == chatId)
                    if (previous.localBody != null) return@withTransaction
                }
                check(dto.chat_id.isEmpty()) { "Групповое E2E на клиенте ещё не поддерживается" }
                val text = crypto.decrypt(dto.sender_id, dto.ciphertext)
                db.messageDao().upsert(row.copy(localBody = crypto.sealHistory(owner, dto.id, text)))
                if (db.chatDao().get(chatId) == null) db.chatDao().upsert(ChatEntity(chatId, "dm", dto.sender_id.take(12)))
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            // Транзакция Signal уже откатилась; ciphertext остаётся доступен для диагностики/восстановления.
            db.withTransaction {
                val previous = db.messageDao().get(dto.id)
                if (previous == null || previous.localBody == null && previous.ciphertext == dto.ciphertext &&
                    previous.senderId == dto.sender_id && previous.chatId == chatId) {
                    db.messageDao().upsert(row.copy(error = if (dto.chat_id.isEmpty())
                        "Не удалось расшифровать: проверьте ключи собеседника" else "Групповое E2E ещё не поддерживается"))
                    if (db.chatDao().get(chatId) == null) db.chatDao().upsert(ChatEntity(chatId,
                        if (dto.chat_id.isEmpty()) "dm" else "group", chatId.take(12)))
                }
            }
        }
    }

    private suspend fun adoptLegacyHistory(owner: String) = db.withTransaction {
        for (old in db.messageDao().legacyMessages()) {
            val dm = old.recipientId.isNotEmpty()
            val chat = if (dm) { if (old.senderId == owner) old.recipientId else old.senderId } else old.chatId
            db.messageDao().upsert(old.copy(ownerId = owner, chatId = chat,
                createdAtMillis = Instant.parse(old.createdAt).toEpochMilli(),
                expiresAtMillis = old.expiresAt?.let { Instant.parse(it).toEpochMilli() }))
            if (db.chatDao().get(chat) == null) db.chatDao().upsert(ChatEntity(chat, if (dm) "dm" else "group", chat.take(12)))
        }
    }

    /**
     * Разбирает открытый текст сообщения: JSON-конверт [MessagePayload] считается
     * конвертом, только если содержит вложение; иначе (включая валидный JSON без
     * поля media) сообщение показывается как обычный текст (обратная совместимость).
     */
    private fun parsePayload(plain: String): MessagePayload {
        if (!plain.startsWith("{")) return MessagePayload(text = plain)
        return runCatching { json.decodeFromString<MessagePayload>(plain) }
            .getOrNull()?.takeIf { it.media != null } ?: MessagePayload(text = plain)
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
