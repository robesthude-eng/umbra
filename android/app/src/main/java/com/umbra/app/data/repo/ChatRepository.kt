package com.umbra.app.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.umbra.app.data.api.*
import com.umbra.app.data.db.AppDatabase
import com.umbra.app.data.db.ChatEntity
import com.umbra.app.data.db.CryptoRecord
import com.umbra.app.data.db.MessageEntity
import com.umbra.app.data.msg.MessageCodec
import com.umbra.app.data.msg.MessageContent
import com.umbra.app.data.session.SessionStore
import com.umbra.app.data.ws.WebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** Фаза приложения относительно сессии. */
enum class SessionPhase { LOGGED_OUT, NEEDS_PROFILE, READY }

/** Строка диалога в списке «Чаты». */
data class Conversation(
    val chatId: String,
    val isGroup: Boolean,
    val title: String,
    val subtitle: String,
    val lastAtMillis: Long,
)

/** Сообщение для UI. */
data class UiMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val createdAtMillis: Long,
    val outgoing: Boolean,
    val failed: Boolean = false,
    val pending: Boolean = false,
)

/** Запись о звонке (история/активный). */
data class CallUi(
    val id: String,
    val peerUserId: String,
    val peerName: String,
    val incoming: Boolean,
    val status: String, // ringing | active | ended | declined | missed
    val createdAtMillis: Long,
)

/** Текущий звонок (входящий/исходящий) — оверлей поверх приложения. */
data class ActiveCall(
    val callId: String,
    val peerUserId: String,
    val peerName: String,
    val incoming: Boolean,
    val ringing: Boolean,
)

/**
 * Репозиторий T1 (v0.4): вход по номеру с кодом из Telegram, облачная история,
 * личные сообщения и группы, звонки (сигналинг). Без Signal: шифрования нет,
 * приватность — «доверяй серверу» (TLS).
 */
class ChatRepository(
    private val context: Context,
    private val api: UmbraApi,
    private val db: AppDatabase,
    private val session: SessionStore,
    private val ws: WebSocketClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val syncMutex = Mutex()
    private val sendMutex = Mutex()
    private val authMutex = Mutex()
    private var pollJob: Job? = null
    private var eventJob: Job? = null
    private var lastFullSyncMillis = 0L

    // ---- состояние сессии ----
    private val _phase = MutableStateFlow(currentPhase())
    val phase: StateFlow<SessionPhase> = _phase

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _syncProblem = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncProblem

    /** Кэш карточек пользователей (имя/аватар), ключ — user id. */
    private val _userCache = MutableStateFlow<Map<String, UserCard>>(emptyMap())
    val userCache: StateFlow<Map<String, UserCard>> = _userCache

    /** Текущий (входящий/исходящий) звонок для оверлея. */
    private val _activeCall = MutableStateFlow<ActiveCall?>(null)
    val activeCall: StateFlow<ActiveCall?> = _activeCall

    // ---------------- сессия ----------------

    private fun currentPhase(): SessionPhase = when {
        !session.isLoggedIn() -> SessionPhase.LOGGED_OUT
        !session.profileComplete() -> SessionPhase.NEEDS_PROFILE
        else -> SessionPhase.READY
    }

    fun me(): String? = session.userId()

    fun accountInfo(): AccountView = AccountView(
        id = session.userId().orEmpty(),
        username = session.username().orEmpty(),
        phone = session.phone().orEmpty(),
        displayName = session.displayName().orEmpty(),
        lastName = session.lastName().orEmpty(),
        avatarMediaId = session.avatarMediaId().orEmpty(),
    )

    private fun auth() = "Bearer " + requireNotNull(session.token()) { "Войдите в аккаунт" }

    /** Шаг 1: запрос 6-значного кода на номер (код уходит владельцу в Telegram). */
    suspend fun requestCode(phone: String) {
        val normalized = requireNotNull(normalizePhone(phone)) { "Некорректный номер. Пример: +7 999 123-45-67" }
        api.requestCode(RequestCodeRequest(normalized))
    }

    /** Шаг 2: проверка кода. Создаёт/находит облачный аккаунт и сохраняет сессию. */
    suspend fun verifyCode(phone: String, code: String): VerifyCodeResponse =
        authMutex.withLock {
            val v = api.verifyCode(VerifyCodeRequest(phone, code.trim()))
            val a = v.account ?: throw IllegalStateException("Сервер не вернул аккаунт")
            session.save(v.token, a.id, a.username, a.phone, a.displayName, a.lastName, a.avatarMediaId)
            db.clearAllTables()
            _phase.value = currentPhase()
            v
        }

    /** Заполнение профиля после регистрации. */
    suspend fun updateProfile(name: String, lastName: String, username: String) {
        val r = api.updateProfile(auth(), UpdateProfileRequest(name, lastName, username))
        session.saveProfile(r.username, r.displayName, r.lastName, null)
        _phase.value = currentPhase()
    }

    /** Загружает выбранное фото как аватар и привязывает к аккаунту. */
    suspend fun uploadAndSetAvatar(uri: Uri) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("не удалось прочитать фото")
        if (bytes.size > 10 * 1024 * 1024) throw IOException("фото больше 10 МБ")
        val part = MultipartBody.Part.createFormData("file", "avatar", bytes.toRequestBody(mime.toMediaType()))
        val typeField = mime.toRequestBody("text/plain".toMediaType())
        val up = api.uploadMedia(auth(), part, typeField)
        if (!up.isSuccessful) throw IOException("загрузка аватара: HTTP ${up.code()}")
        val mediaId = up.body()?.id ?: throw IOException("пустой ответ при загрузке аватара")
        api.setAvatar(auth(), SetAvatarRequest(mediaId))
        session.saveAvatar(mediaId)
        putUserCache(UserCard(id = session.userId().orEmpty(), username = session.username().orEmpty(),
            displayName = session.displayName().orEmpty(), lastName = session.lastName().orEmpty(),
            avatarMediaId = mediaId))
    }

    suspend fun logout() {
        val token = session.token()
        _activeCall.value = null
        stopRealtime()
        try { withTimeout(4000) { token?.let { api.logout("Bearer " + it) } } }
        catch (_: Exception) { }
        session.clear()
        db.clearAllTables()
        _phase.value = SessionPhase.LOGGED_OUT
    }

    suspend fun deleteAccount() {
        val token = session.token()
        stopRealtime()
        try { withTimeout(5000) { token?.let { api.burnAccount("Bearer " + it) } } }
        catch (_: Exception) { }
        session.clear()
        db.clearAllTables()
        _phase.value = SessionPhase.LOGGED_OUT
    }

    // ---------------- кэш пользователей ----------------

    private suspend fun putUserCache(card: UserCard) {
        _userCache.update { it + (card.id to card) }
        db.cryptoDao().put(CryptoRecord(session.userId().orEmpty(), "user", card.id, json.encodeToString(card)))
    }

    suspend fun resolveUser(id: String): UserCard? {
        _userCache.value[id]?.let { return it }
        val card = try { api.user(auth(), id) } catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        putUserCache(card)
        return card
    }

    suspend fun resolveByUsername(username: String): UserCard? = try {
        api.userByUsername(auth(), username.trim().removePrefix("@"))
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    fun titleFor(userId: String): String {
        val c = _userCache.value[userId]
        return c?.fullName()?.ifBlank { c.username } ?: userId.take(12)
    }

    // ---------------- realtime / синхронизация ----------------

    fun startRealtime() {
        if (pollJob?.isActive == true) return
        val token = session.token() ?: return
        eventJob = scope.launch {
            ws.eventFlow.collect { ev ->
                try {
                    when (ev.type) {
                        "connected" -> { _connected.value = true; syncNow(forceFull = true) }
                        "message" -> persist(apiJsonFromMessage(ev.data))
                        "call" -> handleCallEvent(ev.data)
                        "call_status" -> handleCallStatusEvent(ev.data)
                        else -> {}
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { _syncProblem.value = "Синхронизация не завершена. Повторим при восстановлении связи." }
            }
        }
        pollJob = scope.launch {
            while (isActive && session.isLoggedIn()) {
                if (session.token() == null) break
                try {
                    _connected.value = true
                    ws.connect(session.token()!!)
                    syncNow()
                    flushOutbox()
                    _syncProblem.value = null
                } catch (e: CancellationException) { throw e }
                catch (e: HttpException) {
                    if (e.code() == 401) { session.clear(); _phase.value = SessionPhase.LOGGED_OUT; break }
                    _syncProblem.value = "Синхронизация не завершена"
                } catch (_: Exception) {
                    _syncProblem.value = "Нет связи с сервером"
                }
                delay(4000)
            }
            _connected.value = false
        }
        scope.launch {
            ws.connect(token)
            ws.connected.collect { _connected.value = it }
        }
    }

    fun stopRealtime() {
        eventJob?.cancel(); eventJob = null
        pollJob?.cancel(); pollJob = null
        ws.disconnect()
        _connected.value = false
    }

    private fun apiJsonFromMessage(obj: JsonObject): MessageDto =
        json.decodeFromJsonElement(MessageDto.serializer(), obj)

    /** Синхронизация истории: полная при старте, дальше инкрементальная по времени. */
    private suspend fun syncNow(forceFull: Boolean = false) = syncMutex.withLock {
        val owner = session.userId() ?: return@withLock
        val token = session.token() ?: return@withLock
        val newestLocal = db.messageDao().maxCreatedAtMillis(owner) ?: 0L
        val full = forceFull || newestLocal == 0L
        var since: String? = null
        var afterId: String? = null
        if (!full) {
            since = Instant.ofEpochMilli(newestLocal - 30_000L)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
        }
        var pages = 0
        while (true) {
            val page = api.messages("Bearer " + token, since, afterId, 200).messages
            for (dto in page) persist(dto)
            if (page.size < 200) break
            val last = page.last()
            since = Instant.parse(last.createdAt)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
            afterId = last.id
            if (++pages > 2000) break
        }
        if (full) lastFullSyncMillis = System.currentTimeMillis()
        db.messageDao().deleteExpired(System.currentTimeMillis())
    }

    /** Сохраняет сообщение с сервера (DM и группа) в Room. */
    private suspend fun persist(dto: MessageDto) {
        val owner = session.userId() ?: return
        val expiresMillis = dto.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        val createdAtMillis = runCatching { Instant.parse(dto.createdAt).toEpochMilli() }.getOrDefault(0L)
        val chatId = if (dto.chatId.isNotEmpty()) dto.chatId
        else if (dto.senderId == owner) dto.recipientId else dto.senderId
        if (chatId.isEmpty()) return
        db.withTransaction {
            val existing = db.messageDao().get(dto.id)
            if (existing == null) {
                db.messageDao().upsert(MessageEntity(
                    id = dto.id, senderId = dto.senderId, recipientId = dto.recipientId, chatId = chatId,
                    ciphertext = dto.ciphertext, createdAt = dto.createdAt, expiresAt = dto.expiresAt,
                    ownerId = owner, createdAtMillis = createdAtMillis, expiresAtMillis = expiresMillis,
                    deliveryState = "sent",
                ))
            }
            val type = if (dto.chatId.isNotEmpty()) "group" else "dm"
            if (db.chatDao().get(chatId) == null) {
                db.chatDao().upsert(ChatEntity(chatId, type, if (type == "dm") dto.senderId.take(12) else "Группа"))
            }
        }
        if (chatId != owner && dto.chatId.isEmpty()) {
            val peer = if (dto.senderId == owner) dto.recipientId else dto.senderId
            scope.launch { resolveUser(peer); updateDmTitle(peer) }
        }
    }

    private suspend fun updateDmTitle(peerId: String) {
        val card = _userCache.value[peerId] ?: return
        db.chatDao().get(peerId)?.let { db.chatDao().upsert(it.copy(title = card.fullName())) }
    }

    // ---------------- звонки ----------------

    private suspend fun handleCallEvent(obj: JsonObject) {
        val call = json.decodeFromJsonElement(CallDto.serializer(), obj)
        if (call.calleeId == session.userId() && call.status == "ringing") {
            val peer = resolveUser(call.callerId)
            _activeCall.value = ActiveCall(call.id, call.callerId, peer?.fullName() ?: "…", incoming = true, ringing = true)
        }
    }

    private suspend fun handleCallStatusEvent(obj: JsonObject) {
        val callId = obj["call_id"]?.toString()?.trim('"').orEmpty()
        val status = obj["status"]?.toString()?.trim('"').orEmpty()
        _activeCall.value?.let { cur ->
            if (cur.callId == callId && status in listOf("active", "declined", "ended", "missed")) {
                if (status == "active") _activeCall.value = cur.copy(ringing = false)
                else if (status != "active") _activeCall.value = null
            }
        }
    }

    /** Совершить звонок (сигналинг: сервер уведомит собеседника). */
    suspend fun startCall(peerUserId: String) {
        val call = api.initiateCall(auth(), InitiateCallRequest(peerUserId, video = false))
        val peer = resolveUser(peerUserId)
        _activeCall.value = ActiveCall(call.id, peerUserId, peer?.fullName() ?: "…", incoming = false, ringing = true)
    }

    suspend fun setCallStatus(status: String) {
        val call = _activeCall.value ?: return
        try { api.updateCallStatus(auth(), call.callId, CallStatusRequest(status)) } catch (_: Exception) { }
        if (status in listOf("active")) _activeCall.value = call.copy(ringing = false)
        else _activeCall.value = null
    }

    /** История звонков (для вкладки «Звонки»). */
    suspend fun fetchCalls(): List<CallUi> {
        val me = session.userId() ?: return emptyList()
        return try {
            api.calls(auth()).calls.map { c ->
                val peerId = if (c.callerId == me) c.calleeId else c.callerId
                CallUi(c.id, peerId, titleFor(peerId), c.callerId != me, c.status,
                    runCatching { Instant.parse(c.createdAt).toEpochMilli() }.getOrDefault(0L))
            }.sortedByDescending { it.createdAtMillis }
        } catch (_: Exception) { emptyList() }
    }

    // ---------------- чаты и сообщения ----------------

    fun conversations(): Flow<List<Conversation>> = combine(
        db.chatDao().all(),
        db.messageDao().all(session.userId().orEmpty()),
        _userCache,
    ) { chats, msgs, _ ->
        val lastByChat = HashMap<String, MessageEntity>()
        for (m in msgs) {
            val cur = lastByChat[m.chatId]
            if (cur == null || m.createdAtMillis >= cur.createdAtMillis) lastByChat[m.chatId] = m
        }
        val byId = chats.associateBy { it.id }
        val result = ArrayList<Conversation>()
        val seen = HashSet<String>()
        for ((chatId, last) in lastByChat) {
            seen.add(chatId)
            val chat = byId[chatId]
            val group = chat?.type == "group"
            val title = if (group) chat.title else titleFor(chatId)
            val subtitle = MessageCodec.plainText(last.ciphertext).ifBlank { "⦙" }
            result.add(Conversation(chatId, group, title, subtitle, last.createdAtMillis))
        }
        // группы без сообщений тоже показываем
        for (chat in chats) if (chat.type == "group" && !seen.contains(chat.id)) {
            result.add(Conversation(chat.id, true, chat.title, "Группа создана", 0L))
        }
        result.sortedByDescending { it.lastAtMillis }
    }.flowOn(Dispatchers.IO)

    fun chats(): Flow<List<ChatEntity>> = db.chatDao().all()

    fun messagesFor(chatId: String): Flow<List<UiMessage>> =
        db.messageDao().messagesFor(session.userId().orEmpty(), chatId).map { list ->
            list.map { e ->
                val text = MessageCodec.plainText(e.ciphertext).ifBlank { "[пустое сообщение]" }
                UiMessage(e.id, e.senderId, text, e.createdAtMillis,
                    outgoing = e.senderId == session.userId() && e.ownerId == e.senderId,
                    failed = e.deliveryState == "failed", pending = e.deliveryState == "pending")
            }
        }.flowOn(Dispatchers.IO)

    /** Создать/получить личный чат с пользователем. */
    suspend fun openDm(peerUserId: String) {
        db.chatDao().get(peerUserId) ?: run {
            val card = resolveUser(peerUserId)
            db.chatDao().upsert(ChatEntity(peerUserId, "dm", card?.fullName() ?: peerUserId.take(12)))
        }
    }

    suspend fun createGroup(title: String, memberIds: List<String>) {
        val chat = api.createGroup(auth(), CreateChatRequest(title))
        db.chatDao().upsert(ChatEntity(chat.id, "group", chat.title))
        for (id in memberIds) if (id != session.userId()) {
            try { api.addMember(auth(), chat.id, AddMemberRequest(id)) } catch (_: Exception) { }
        }
    }

    suspend fun groupMembers(chatId: String): List<UserCard> {
        val members = try { api.chatMembers(auth(), chatId).members } catch (_: Exception) { return emptyList() }
        val cards = ArrayList<UserCard>()
        for (m in members) resolveUser(m.userId)?.let { cards.add(it) }
        return cards
    }

    /** Отправка текста: в ЛС или группу; локальная запись → outbox. */
    suspend fun sendText(chatId: String, text: String) {
        val owner = session.userId() ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val id = UUID.randomUUID().toString()
        val content = MessageContent(kind = MessageContent.KIND_TEXT, text = trimmed)
        val envelope = MessageCodec.encode(content)
        val now = Instant.now().toEpochMilli()
        val chat = db.chatDao().get(chatId)
        db.messageDao().upsert(MessageEntity(
            id = "local:$id", senderId = owner, recipientId = if (chat?.type == "dm") chatId else "",
            chatId = chatId, ciphertext = envelope, createdAt = Instant.now().toString(),
            expiresAt = null, ownerId = owner, createdAtMillis = now,
            deliveryState = "pending", clientId = id,
        ))
        scope.launch { flushOutbox() }
    }

    private suspend fun flushOutbox() = sendMutex.withLock {
        val owner = session.userId() ?: return@withLock
        val token = session.token() ?: return@withLock
        for (m in db.messageDao().pending(owner)) {
            val clientId = m.clientId ?: continue
            try {
                val chat = db.chatDao().get(m.chatId)
                val sent = if (chat?.type == "group") {
                    api.sendChatMessage("Bearer " + token, m.chatId, SendChatMessageRequest(m.ciphertext, clientId))
                } else {
                    api.sendMessage("Bearer " + token, SendMessageRequest(m.recipientId, m.ciphertext, clientId))
                }
                val newMillis = runCatching { Instant.parse(sent.createdAt).toEpochMilli() }.getOrDefault(m.createdAtMillis)
                db.withTransaction {
                    db.messageDao().upsert(m.copy(id = sent.id, createdAtMillis = newMillis,
                        deliveryState = "sent", error = null, clientId = null))
                    db.messageDao().delete(m.id) // удалить локальный дубль
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val retryable = e is IOException || (e is HttpException && (e.code() >= 500 || e.code() == 429 || e.code() == 401))
                db.withTransaction {
                    db.messageDao().get(m.id)?.let { cur ->
                        db.messageDao().upsert(cur.copy(deliveryState = if (retryable) "pending" else "failed",
                            error = if (retryable) null else "Не отправлено. Проверьте сеть."))
                    }
                }
                if (e is HttpException && e.code() == 401) { session.clear(); _phase.value = SessionPhase.LOGGED_OUT }
            }
        }
    }

    // ---------------- утилиты ----------------

    /** Держит приложение живым при пересоздании Activity. */
    fun close() {
        stopRealtime()
        scope.coroutineContext[Job]?.cancel()
        ws.close()
    }

    private fun normalizePhone(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        var hasPlus = s[0] == '+'
        var digits = buildString {
            for ((i, c) in s.withIndex()) {
                when {
                    c in '0'..'9' -> append(c)
                    i == 0 && c == '+' -> Unit
                    c == ' ' || c == '-' || c == '(' || c == ')' || c == '.' -> Unit
                    else -> return null
                }
            }
        }
        if (digits.startsWith("00")) { digits = digits.substring(2); hasPlus = true }
        if (!hasPlus) {
            when {
                digits.length == 11 && digits[0] == '8' -> digits = "7" + digits.substring(1)
                digits.length == 10 -> digits = "7" + digits
            }
        }
        if (digits.length < 7 || digits.length > 15 || digits[0] == '0') return null
        return "+$digits"
    }

    suspend fun avatarBytes(mediaId: String): ByteArray? = try {
        val r = api.downloadMedia(auth(), mediaId)
        if (!r.isSuccessful) null else r.body()?.use { it.bytes() }
    } catch (_: Exception) { null }

    companion object {
        const val MEDIA_CACHE_DIR = "media"
    }
}
