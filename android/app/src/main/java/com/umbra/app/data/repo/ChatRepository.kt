package com.umbra.app.data.repo

import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.room.withTransaction
import com.umbra.app.data.AvatarImages
import com.umbra.app.data.InputRules
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
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
    val error: String? = null,
    val stableId: String = id,
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

/** Cloud messenger repository. Message envelopes are not end-to-end encrypted. */
class ChatRepository(
    private val context: Context,
    private val api: UmbraApi,
    private val db: AppDatabase,
    private val session: SessionStore,
    private val ws: WebSocketClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val authMutex = Mutex()
    private val syncMutex = Mutex()
    private val sendMutex = Mutex()
    private val profileMutex = Mutex()
    private val callMutex = Mutex()
    private var pollJob: Job? = null
    private var eventJob: Job? = null
    private var outboxJob: Job? = null
    private var lastFullSyncMillis = 0L
    private val avatarCache = object : LruCache<String, ByteArray>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    private data class SessionKey(val owner: String, val token: String) {
        val auth get() = "Bearer $token"
    }
    private fun key() = SessionKey(
        requireNotNull(session.userId()) { "Войдите в аккаунт" },
        requireNotNull(session.token()) { "Войдите в аккаунт" },
    )
    private fun isCurrent(key: SessionKey) = session.userId() == key.owner && session.token() == key.token

    private fun currentPhase() = when {
        !session.isLoggedIn() -> SessionPhase.LOGGED_OUT
        !session.profileComplete() -> SessionPhase.NEEDS_PROFILE
        else -> SessionPhase.READY
    }
    private val _phase = MutableStateFlow(currentPhase())
    val phase: StateFlow<SessionPhase> = _phase.asStateFlow()
    val connected = ws.connected
    private val _syncProblem = MutableStateFlow<String?>(null)
    val syncError = _syncProblem.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing = _syncing.asStateFlow()
    private val _userCache = MutableStateFlow<Map<String, UserCard>>(emptyMap())
    val userCache = _userCache.asStateFlow()
    private val _activeCall = MutableStateFlow<ActiveCall?>(null)
    val activeCall = _activeCall.asStateFlow()
    private val _calls = MutableStateFlow<List<CallUi>>(emptyList())
    val calls = _calls.asStateFlow()
    private val _account = MutableStateFlow(accountInfo())
    val account = _account.asStateFlow()

    fun me(): String? = session.userId()
    fun accountInfo() = AccountView(
        id = session.userId().orEmpty(), username = session.username().orEmpty(),
        phone = session.phone().orEmpty(), displayName = session.displayName().orEmpty(),
        lastName = session.lastName().orEmpty(), avatarMediaId = session.avatarMediaId().orEmpty(),
    )

    /** Serialize local commits with sign-out/account switching; stale responses cannot leak into another account. */
    private suspend fun <T> commit(key: SessionKey, block: suspend () -> T): T = authMutex.withLock {
        if (!isCurrent(key)) throw CancellationException("Session changed")
        block()
    }

    private suspend fun <T> request(key: SessionKey, block: suspend (String) -> T): T {
        if (!isCurrent(key)) throw CancellationException("Session changed")
        try { return block(key.auth) }
        catch (e: HttpException) {
            if (e.code() == 401) withContext(NonCancellable) {
                authMutex.withLock { if (isCurrent(key)) clearSessionLocally() }
            }
            throw e
        }
    }

    private fun clearSessionLocally() {
        session.clearToken()
        stopRealtime()
        _activeCall.value = null
        _calls.value = emptyList()
        _userCache.value = emptyMap()
        avatarCache.evictAll()
        _syncProblem.value = null
        lastFullSyncMillis = 0L
        _phase.value = SessionPhase.LOGGED_OUT
    }

    suspend fun requestCode(phone: String) {
        val normalized = requireNotNull(InputRules.normalizePhone(phone)) { "Проверьте номер телефона. Пример: +7 999 123-45-67" }
        api.requestCode(RequestCodeRequest(normalized))
    }

    suspend fun verifyCode(phone: String, code: String): VerifyCodeResponse = scope.async {
        val normalized = requireNotNull(InputRules.normalizePhone(phone)) { "Проверьте номер телефона" }
        require(code.matches(Regex("[0-9]{6}"))) { "Введите код из 6 цифр" }
        val result = api.verifyCode(VerifyCodeRequest(normalized, code))
        val a = requireNotNull(result.account) { "Сервер не вернул аккаунт. Запросите новый код." }
        check(result.token.isNotBlank() && a.id.isNotBlank()) { "Сервер не завершил вход. Запросите новый код." }
        withContext(NonCancellable) {
            authMutex.withLock {
                stopRealtime()
                // Reauthentication to the same account must preserve unsent messages.
                if (session.userId() != a.id) db.clearAllTables()
                _userCache.value = emptyMap()
                avatarCache.evictAll()
                session.save(result.token, a.id, a.username, a.phone, a.displayName, a.lastName, a.avatarMediaId)
                _account.value = accountInfo()
                lastFullSyncMillis = 0L
                _phase.value = currentPhase()
            }
        }
        result
    }.await()

    suspend fun updateProfile(name: String, lastName: String, username: String) {
        require(name.trim().isNotEmpty() && name.trim().codePointCount(0, name.trim().length) <= 64) { "Имя должно содержать от 1 до 64 символов" }
        require(lastName.trim().codePointCount(0, lastName.trim().length) <= 64) { "Фамилия — не более 64 символов" }
        require(InputRules.validUsername(username)) { "Никнейм: 3–32 латинские буквы, цифры или знак _" }
        val key = key()
        scope.async {
            profileMutex.withLock {
                val r = request(key) { api.updateProfile(it, UpdateProfileRequest(name.trim(), lastName.trim(), InputRules.username(username))) }
                commit(key) {
                    session.saveProfile(r.username, r.displayName, r.lastName, session.avatarMediaId())
                    _account.value = accountInfo()
                    putUserCacheLocked(key, UserCard(key.owner, r.username, r.displayName, r.lastName, session.avatarMediaId().orEmpty()))
                    _phase.value = currentPhase()
                }
            }
        }.await()
    }

    suspend fun uploadAndSetAvatar(uri: Uri) {
        val key = key()
        val bytes = AvatarImages.read(context, uri)
        val bitmap = AvatarImages.decode(bytes) ?: throw IllegalArgumentException("Не удалось прочитать изображение. Выберите другое фото.")
        bitmap.recycle()
        val mime = context.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        profileMutex.withLock {
            val part = MultipartBody.Part.createFormData("file", "avatar", bytes.toRequestBody(mime.toMediaType()))
            val up = request(key) {
                val response = api.uploadMedia(it, part, mime.toRequestBody("text/plain".toMediaType()))
                if (!response.isSuccessful) throw HttpException(response)
                response.body() ?: throw IOException("Empty upload response")
            }
            check(up.id.isNotBlank()) { "Сервер не сохранил фото. Попробуйте ещё раз." }
            request(key) { api.setAvatar(it, SetAvatarRequest(up.id)) }
            commit(key) {
                session.saveAvatar(up.id)
                avatarCache.put(up.id, bytes)
                _account.value = accountInfo()
                val a = accountInfo()
                putUserCacheLocked(key, UserCard(a.id, a.username, a.displayName, a.lastName, a.avatarMediaId))
            }
        }
    }

    suspend fun logout() {
        val oldToken = session.token()
        authMutex.withLock { clearSessionLocally() }
        // Local sign-out is immediate, including when offline. Revocation is best effort.
        scope.launch {
            try { withTimeout(4000) { oldToken?.let { api.logout("Bearer $it") } } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Server token expires according to its TTL. */ }
        }
    }

    suspend fun deleteAccount() {
        val key = key()
        scope.async {
            // A failed/ambiguous server response must never be presented as successful deletion.
            request(key) { api.burnAccount(it) }
            withContext(NonCancellable) {
                commit(key) {
                    clearSessionLocally()
                    db.clearAllTables()
                    session.clear()
                    _account.value = accountInfo()
                }
            }
        }.await()
    }

    private suspend fun putUserCacheLocked(key: SessionKey, card: UserCard) {
        _userCache.update { it + (card.id to card) }
        db.cryptoDao().put(CryptoRecord(key.owner, "user", card.id, json.encodeToString(card)))
    }

    suspend fun resolveUser(id: String): UserCard? = resolveUser(key(), id)
    private suspend fun resolveUser(key: SessionKey, id: String, force: Boolean = false): UserCard? {
        if (!force) _userCache.value[id]?.let { return it }
        val card = try { request(key) { api.user(it, id) } }
        catch (e: HttpException) { if (e.code() == 404) return null else throw e }
        commit(key) {
            putUserCacheLocked(key, card)
            db.chatDao().get(id)?.takeIf { it.type == "dm" }?.let { db.chatDao().upsert(it.copy(title = card.fullName())) }
        }
        return card
    }

    suspend fun resolveByUsername(username: String): UserCard? {
        require(InputRules.validUsername(username)) { "Никнейм: 3–32 латинские буквы, цифры или знак _" }
        val key = key()
        val card = try { request(key) { api.userByUsername(it, InputRules.username(username)) } }
        catch (e: HttpException) { if (e.code() == 404) return null else throw e }
        commit(key) { putUserCacheLocked(key, card) }
        return card
    }

    fun titleFor(userId: String): String = _userCache.value[userId]?.fullName()?.ifBlank { "Пользователь" } ?: "Пользователь"

    fun startRealtime() {
        if (pollJob?.isActive == true || !session.isLoggedIn()) return
        val key = key()
        eventJob = scope.launch {
            ws.eventFlow.collect { event ->
                if (!isCurrent(key) || event.token != key.token) return@collect
                try {
                    when (event.type) {
                        "connected" -> refresh(forceFull = true)
                        "message" -> persist(key, json.decodeFromJsonElement(MessageDto.serializer(), event.data))
                        "call" -> handleCallEvent(key, json.decodeFromJsonElement(CallDto.serializer(), event.data))
                        "call_status" -> {
                            val callId = event.data["call_id"]?.toString()?.trim('"')
                            val status = event.data["status"]?.toString()?.trim('"')
                            commit(key) {
                                _activeCall.value?.takeIf { it.callId == callId }?.let {
                                    _activeCall.value = if (status == "active") it.copy(ringing = false) else null
                                }
                            }
                            fetchCalls()
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (isCurrent(key)) _syncProblem.value = "Не удалось обновить данные. Повторим при восстановлении связи." }
            }
        }
        pollJob = scope.launch {
            val cached = db.cryptoDao().all(key.owner, "user").mapNotNull {
                runCatching { json.decodeFromString<UserCard>(it.value) }.getOrNull()
            }.associateBy { it.id }
            commit(key) { _userCache.value = cached + _userCache.value }
            ws.connect(key.token)
            while (isActive && isCurrent(key)) {
                try { refresh() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* refresh publishes the error; polling retries. */ }
                delay(4000)
            }
        }
    }

    fun stopRealtime() {
        eventJob?.cancel(); eventJob = null
        pollJob?.cancel(); pollJob = null
        outboxJob?.cancel(); outboxJob = null
        ws.disconnect()
        _syncing.value = false
    }

    suspend fun refresh(forceFull: Boolean = false) {
        val key = key()
        try {
            syncNow(key, forceFull)
            flushOutbox()
            commit(key) { _syncProblem.value = null }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (isCurrent(key)) _syncProblem.value = "Не удалось обновить данные. Проверьте подключение и повторите."
            throw e
        }
    }

    private suspend fun syncNow(key: SessionKey, forceFull: Boolean) = syncMutex.withLock {
        _syncing.value = true
        try {
            val newest = db.messageDao().maxCreatedAtMillis(key.owner) ?: 0L
            val now = System.currentTimeMillis()
            val full = forceFull || newest == 0L || now - lastFullSyncMillis >= 15 * 60_000L
            val groups = request(key) { api.chats(it).chats }
            commit(key) { db.withTransaction {
                val remoteIds = groups.map { it.id }.toSet()
                for (old in db.chatDao().snapshot()) {
                    if (old.type != "dm" && old.id !in remoteIds) db.chatDao().upsert(old.copy(type = "unavailable"))
                }
                for (g in groups) db.chatDao().upsert(ChatEntity(g.id, g.type, g.title))
            } }
            // Timers continue to work independently of REST connectivity.
            commit(key) { db.messageDao().deleteExpired(now) }
            var since: String? = if (full) null else Instant.ofEpochMilli((newest - 30_000L).coerceAtLeast(0L)).toString()
            var afterId: String? = null
            var pages = 0
            val peers = linkedSetOf<String>()
            while (true) {
                val page = request(key) { api.messages(it, since, afterId, 200).messages }
                for (dto in page) {
                    persist(key, dto)
                    if (dto.chatId.isEmpty()) peers.add(if (dto.senderId == key.owner) dto.recipientId else dto.senderId)
                }
                if (page.size < 200) break
                val last = page.last()
                check(last.createdAt != since || last.id != afterId) { "Сервер повторяет страницу истории. Попробуйте позже." }
                // The API cursor includes fractional seconds. Truncating them repeats the same page forever.
                since = last.createdAt
                afterId = last.id
                check(++pages <= 2000) { "История слишком велика для одного обновления" }
            }
            if (full) profileMutex.withLock {
                val a = request(key) { api.account(it) }
                commit(key) {
                    session.saveProfile(a.username, a.displayName, a.lastName, a.avatarMediaId)
                    _account.value = accountInfo()
                }
            }
            for (peer in peers.filter { it.isNotBlank() && it != key.owner }) {
                try { resolveUser(key, peer, force = full) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Cached messages remain usable if a profile lookup fails. */ }
            }
            if (full) commit(key) { lastFullSyncMillis = now }
        } finally { _syncing.value = false }
    }

    private suspend fun persist(key: SessionKey, dto: MessageDto) = commit(key) {
        val expires = dto.expiresAt?.let { Instant.parse(it).toEpochMilli() }
        val created = Instant.parse(dto.createdAt).toEpochMilli()
        val chatId = dto.chatId.ifEmpty { if (dto.senderId == key.owner) dto.recipientId else dto.senderId }
        if (chatId.isBlank()) return@commit
        db.withTransaction {
            val local = dto.clientMessageId?.takeIf { dto.senderId == key.owner && it.isNotBlank() }
                ?.let { db.messageDao().byClientId(key.owner, it) }
            if (expires == null || expires > System.currentTimeMillis()) {
                db.messageDao().upsert(MessageEntity(
                    dto.id, dto.senderId, dto.recipientId, chatId, dto.ciphertext, dto.createdAt, dto.expiresAt,
                    ownerId = key.owner, createdAtMillis = created, expiresAtMillis = expires,
                    deliveryState = "sent", clientId = dto.clientMessageId ?: local?.clientId,
                ))
            } else db.messageDao().delete(dto.id)
            if (local != null && local.id != dto.id) db.messageDao().delete(local.id)
            if (db.chatDao().get(chatId) == null) db.chatDao().upsert(ChatEntity(
                chatId, if (dto.chatId.isEmpty()) "dm" else "group",
                if (dto.chatId.isEmpty()) titleFor(chatId) else "Группа",
            ))
        }
    }

    private suspend fun handleCallEvent(key: SessionKey, call: CallDto) {
        if (call.calleeId != key.owner || call.status != "ringing") return
        if (_activeCall.value?.callId == call.id) return
        if (_activeCall.value != null) {
            request(key) { api.updateCallStatus(it, call.id, CallStatusRequest("declined")) }
            return
        }
        commit(key) { _activeCall.value = ActiveCall(call.id, call.callerId, titleFor(call.callerId), true, true) }
    }

    suspend fun startCall(peerUserId: String) = callMutex.withLock {
        val key = key()
        require(peerUserId != key.owner) { "Нельзя позвонить самому себе" }
        check(_activeCall.value == null) { "Сначала завершите текущий вызов" }
        val call = request(key) { api.initiateCall(it, InitiateCallRequest(peerUserId)) }
        commit(key) { _activeCall.value = ActiveCall(call.id, peerUserId, titleFor(peerUserId), false, true) }
    }

    suspend fun setCallStatus(status: String) = callMutex.withLock {
        val key = key()
        val call = _activeCall.value ?: return@withLock
        val result = request(key) { api.updateCallStatus(it, call.callId, CallStatusRequest(status)) }
        commit(key) {
            if (_activeCall.value?.callId == call.callId) {
                _activeCall.value = if (result.status == "active") call.copy(ringing = false) else null
            }
        }
    }

    fun dismissCallLocally() { _activeCall.value = null }

    suspend fun fetchCalls(): List<CallUi> {
        val key = key()
        val remote = request(key) { api.calls(it).calls }
        for (peer in remote.map { if (it.callerId == key.owner) it.calleeId else it.callerId }.distinct()) {
            try { resolveUser(key, peer) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Show a fallback name without hiding the history. */ }
        }
        val result = remote.map { c ->
            val peer = if (c.callerId == key.owner) c.calleeId else c.callerId
            CallUi(c.id, peer, titleFor(peer), c.callerId != key.owner, c.status, Instant.parse(c.createdAt).toEpochMilli())
        }.sortedByDescending { it.createdAtMillis }
        commit(key) {
            _calls.value = result
            _activeCall.value?.let { active ->
                remote.firstOrNull { it.id == active.callId }?.let { c ->
                    _activeCall.value = when (c.status) {
                        "ringing" -> active
                        "active" -> active.copy(ringing = false)
                        else -> null
                    }
                }
            }
        }
        return result
    }

    fun conversations(): Flow<List<Conversation>> = combine(db.chatDao().all(), db.messageDao().all(me().orEmpty()), _userCache) { chats, msgs, users ->
        val last = msgs.groupBy { it.chatId }.mapValues { (_, list) -> list.maxBy { it.createdAtMillis } }
        chats.map { chat ->
            val message = last[chat.id]
            val group = chat.type != "dm"
            val title = if (group) chat.title else users[chat.id]?.fullName() ?: chat.title
            val subtitle = if (chat.type == "unavailable") "Нет доступа к группе" else message?.let { MessageCodec.plainText(it.ciphertext) } ?: "Пока нет сообщений"
            Conversation(chat.id, group, title, subtitle, message?.createdAtMillis ?: 0L)
        }.sortedByDescending { it.lastAtMillis }
    }.flowOn(Dispatchers.IO)

    fun chats() = db.chatDao().all()
    fun messagesFor(chatId: String): Flow<List<UiMessage>> {
        val owner = me().orEmpty()
        return db.messageDao().messagesFor(owner, chatId).map { rows -> rows.map { e ->
            UiMessage(e.id, e.senderId, MessageCodec.plainText(e.ciphertext), e.createdAtMillis,
                e.senderId == owner && e.ownerId == owner, e.deliveryState == "failed", e.deliveryState == "pending",
                e.error, e.clientId?.let { "${e.senderId}:$it" } ?: e.id)
        } }.flowOn(Dispatchers.IO)
    }

    suspend fun openDm(peerUserId: String) {
        val key = key()
        require(peerUserId != key.owner) { "Вы указали свой никнейм. Введите никнейм собеседника." }
        val card = resolveUser(key, peerUserId) ?: throw IllegalArgumentException("Пользователь не найден")
        commit(key) { db.chatDao().upsert(ChatEntity(peerUserId, "dm", card.fullName())) }
    }

    suspend fun createGroup(title: String): String {
        require(title.trim().isNotEmpty() && title.trim().toByteArray(Charsets.UTF_8).size <= 200) { "Название группы слишком длинное. Сократите его." }
        val key = key()
        val chat = request(key) { api.createGroup(it, CreateChatRequest(title.trim())) }
        withContext(NonCancellable) { commit(key) { db.chatDao().upsert(ChatEntity(chat.id, "group", chat.title)) } }
        return chat.id
    }

    suspend fun addGroupMembers(chatId: String, memberIds: List<String>) {
        val key = key()
        for (id in memberIds.distinct().filter { it != key.owner }) {
            try { request(key) { api.addMember(it, chatId, AddMemberRequest(id)) } }
            catch (e: HttpException) { if (e.code() != 409) throw e }
        }
    }

    suspend fun groupMembers(chatId: String): List<UserCard> {
        val key = key()
        return request(key) { api.chatMembers(it, chatId).members }.map { member ->
            resolveUser(key, member.userId, force = true) ?: UserCard(member.userId, displayName = "Удалённый пользователь")
        }
    }

    suspend fun sendText(chatId: String, text: String) {
        val key = key()
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "Введите сообщение" }
        require(trimmed.length <= InputRules.MAX_TEXT_LENGTH) { "Сообщение слишком длинное (максимум 16 000 символов)" }
        commit(key) {
            val chat = requireNotNull(db.chatDao().get(chatId)) { "Сначала откройте диалог" }
            check(chat.type != "unavailable") { "Доступ к группе прекращён. Обратитесь к её владельцу." }
            val id = UUID.randomUUID().toString()
            val now = Instant.now()
            db.messageDao().upsert(MessageEntity(
                "local:$id", key.owner, if (chat.type == "dm") chatId else "", chatId,
                MessageCodec.encode(MessageContent(text = trimmed)), now.toString(), null,
                ownerId = key.owner, createdAtMillis = now.toEpochMilli(), deliveryState = "pending", clientId = id,
            ))
        }
        requestOutbox()
    }

    suspend fun retryMessage(id: String) {
        val key = key()
        commit(key) {
            val m = db.messageDao().get(id) ?: return@commit
            if (m.ownerId == key.owner && m.senderId == key.owner && m.deliveryState == "failed")
                db.messageDao().upsert(m.copy(deliveryState = "pending", error = null))
        }
        requestOutbox()
    }

    private fun requestOutbox() {
        if (outboxJob?.isActive == true) return
        outboxJob = scope.launch {
            try { flushOutbox() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _syncProblem.value = "Отправка приостановлена. Сообщения сохранены на устройстве." }
        }
    }

    internal suspend fun flushOutbox() = sendMutex.withLock {
        val key = key()
        while (isCurrent(key)) {
            val pending = db.messageDao().pending(key.owner)
            if (pending.isEmpty()) break
            for (m in pending) {
                val clientId = m.clientId
                if (clientId == null) {
                    commit(key) { db.messageDao().upsert(m.copy(deliveryState = "failed", error = "Не удалось восстановить отправку. Скопируйте текст и отправьте заново.")) }
                    continue
                }
                try {
                    val sent = request(key) { auth ->
                        if (m.recipientId.isEmpty()) api.sendChatMessage(auth, m.chatId, SendChatMessageRequest(m.ciphertext, clientId))
                        else api.sendMessage(auth, SendMessageRequest(m.recipientId, m.ciphertext, clientId))
                    }
                    persist(key, sent.copy(clientMessageId = clientId))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val retryable = e is IOException || e is HttpException && (e.code() >= 500 || e.code() in listOf(408, 429))
                    commit(key) { db.messageDao().get(m.id)?.let { current ->
                        if (current.deliveryState == "pending") db.messageDao().upsert(current.copy(
                            deliveryState = if (retryable) "pending" else "failed",
                            error = if (retryable) null else "Не отправлено. Проверьте доступ к чату и повторите.",
                        ))
                    } }
                    if (retryable) throw e // Preserve ordering and retry later instead of hammering an offline server.
                }
            }
        }
    }

    suspend fun avatarBytesCached(mediaId: String): ByteArray? {
        avatarCache.get(mediaId)?.let { return it }
        val key = key()
        val bytes = request(key) {
            val response = api.downloadMedia(it, mediaId)
            if (response.code() == 404) { response.errorBody()?.close(); return@request null }
            if (!response.isSuccessful) throw HttpException(response)
            withContext(Dispatchers.IO) { response.body()?.use { body -> body.byteStream().use(AvatarImages::readLimited) } }
        } ?: return null
        commit(key) { avatarCache.put(mediaId, bytes) }
        return bytes
    }

    suspend fun deleteExpiredMessages() {
        val key = key()
        commit(key) { db.messageDao().deleteExpired(System.currentTimeMillis()) }
    }

    fun close() { stopRealtime(); scope.cancel(); ws.close() }

    companion object { const val MEDIA_CACHE_DIR = "media" }
}
