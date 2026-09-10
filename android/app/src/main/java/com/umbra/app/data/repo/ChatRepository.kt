package com.umbra.app.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.room.withTransaction
import com.umbra.app.data.AvatarImages
import com.umbra.app.data.InputRules
import com.umbra.app.data.api.*
import com.umbra.app.data.call.CallNotifications
import com.umbra.app.data.db.AppDatabase
import com.umbra.app.data.db.ChatEntity
import com.umbra.app.data.db.CryptoRecord
import com.umbra.app.data.db.MessageEntity
import com.umbra.app.data.media.Attachments
import com.umbra.app.data.msg.ATTACHMENT_KINDS
import com.umbra.app.data.msg.MediaContent
import com.umbra.app.data.msg.MessageCodec
import com.umbra.app.data.msg.MessageContent
import com.umbra.app.data.push.PushService
import com.umbra.app.data.session.SessionStore
import com.umbra.app.data.voice.VoiceRecorder
import com.umbra.app.data.voice.VoiceRecording
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.io.InputStream
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
    /** Заполнено только у голосовых сообщений. */
    val voice: VoiceMessage? = null,
    /** Заполнено у сообщений с фото, видео или файлом. */
    val attachment: UiAttachment? = null,
)

/**
 * Голосовое сообщение для UI.
 * mediaId == null — запись ещё не загружена на сервер, играется локальный файл.
 * localPath == null — своей копии нет, файл будет скачан при первом прослушивании.
 */
data class VoiceMessage(
    val mediaId: String?,
    val localPath: String?,
    val durationMs: Long,
    val sizeBytes: Long,
)

/**
 * Вложение для UI: фото, видео или файл.
 * mediaId == null — файл ещё не загружен на сервер, показываем локальную копию.
 * localPath == null — своей копии нет, файл скачается при открытии.
 */
data class UiAttachment(
    val kind: String,
    val mediaId: String?,
    val localPath: String?,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
) {
    val isImage: Boolean get() = kind == MessageContent.KIND_IMAGE
    val isVideo: Boolean get() = kind == MessageContent.KIND_VIDEO
}

/** Запись о звонке (история/активный). */
data class CallUi(
    val id: String,
    val peerUserId: String,
    val peerName: String,
    val incoming: Boolean,
    val status: String, // ringing | active | ended | declined | missed
    val createdAtMillis: Long,
    val video: Boolean = false,
)

/** Текущий звонок (входящий/исходящий) — экран поверх приложения. */
data class ActiveCall(
    val callId: String,
    val peerUserId: String,
    val peerName: String,
    val incoming: Boolean,
    val ringing: Boolean,
    /** Видеозвонок: камера включается сразу после соединения. */
    val video: Boolean = false,
    /** Свой id: по нему движок решает, кто кому шлёт offer. */
    val selfUserId: String = "",
    /** Кто начал звонок: его предложение принимают остальные. */
    val callerUserId: String = "",
    /** Собеседники без себя: в групповом звонке их до трёх. */
    val peers: List<String> = emptyList(),
    /**
     * Этот телефон уже принял звонок (или сам его начал). Признак
     * свой, а не общий: в группе статус записи становится «active» от ответа
     * любого участника, и раньше это снимало гудок у ещё не ответившего
     * человека — движок включал ему микрофон без согласия.
     */
    val accepted: Boolean = false,
) {
    /** Звонок больше чем на двоих. */
    val group: Boolean get() = peers.size > 1
}

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

    /**
     * Сигналы WebRTC текущего звонка (SDP и ICE-кандидаты).
     *
     * Буфер нужен, потому что ICE-кандидаты приходят пачкой сразу после
     * соединения, а движок звонка подписывается чуть позже.
     */
    private val _callSignals = MutableSharedFlow<CallSignalEvent>(replay = 0, extraBufferCapacity = 64)
    val callSignals = _callSignals.asSharedFlow()
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
        // Гасим экран входящего и забываем токен: чужие уведомления не нужны.
        CallNotifications.cancel(context)
        pushToken = null
        iceCache = null
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
                if (session.userId() != a.id) { db.clearAllTables(); clearLocalMediaFiles() }
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
        val oldPushToken = pushToken
        authMutex.withLock { clearSessionLocally() }
        // Токен FCM больше не наш: иначе уведомления придут на чужой аккаунт.
        PushService.dropToken(context)
        scope.launch {
            try {
                withTimeout(4000) {
                    if (oldToken != null && !oldPushToken.isNullOrBlank()) {
                        api.deletePushDevice("Bearer $oldToken", PushDeviceRequest(oldPushToken))
                    }
                }
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Токен перестанет действовать вместе с сессией. */ }
        }
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
                    clearLocalMediaFiles()
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
        // Сервер должен знать, куда присылать push, пока приложение закрыто.
        PushService.syncToken(context)
        val key = key()
        eventJob = scope.launch {
            ws.eventFlow.collect { event ->
                if (!isCurrent(key) || event.token != key.token) return@collect
                try {
                    when (event.type) {
                        "connected" -> refresh(forceFull = true)
                        "message" -> persist(key, json.decodeFromJsonElement(MessageDto.serializer(), event.data))
                        "call" -> handleCallEvent(key, json.decodeFromJsonElement(CallDto.serializer(), event.data))
                        "call_signal" -> {
                            val signal = json.decodeFromJsonElement(CallSignalEvent.serializer(), event.data)
                            // Сигналы чужого (уже завершённого) звонка игнорируем.
                            if (_activeCall.value?.callId == signal.callId) _callSignals.tryEmit(signal)
                        }
                        "call_status" -> {
                            val callId = event.data["call_id"]?.toString()?.trim('"')
                            val status = event.data["status"]?.toString()?.trim('"')
                            // from — кто именно сменил статус: в группе это важно.
                            val from = event.data["from"]?.toString()?.trim('"').orEmpty()
                            commit(key) {
                                _activeCall.value?.takeIf { it.callId == callId }?.let {
                                    _activeCall.value = applyCallStatus(it, status, from)
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
            // Живой WebSocket уже доставляет новые сообщения (eventJob -> persist),
            // поэтому частый REST-опрос не нужен и лишь заставлял экран мигать
            // «Обновление… / Загружаем диалоги…» каждые 4 секунды. Стартовый вызов —
            // с индикацией; дальше: при живом WS — тихая редкая страховка, при разрыве —
            // явный refresh с интервалом 5 c (для восстановления и индикации ошибки).
            var started = false
            while (isActive && isCurrent(key)) {
                try {
                    if (started && ws.connected.value) {
                        syncNow(key, forceFull = false, notify = false)
                        flushOutbox()
                        commit(key) { _syncProblem.value = null }
                    } else {
                        refresh()
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* refresh публикует ошибку; поллинг повторит. */ }
                started = true
                val live = ws.connected.value
                delay(if (live) 60_000L else 5_000L)
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

    private suspend fun syncNow(key: SessionKey, forceFull: Boolean, notify: Boolean = true) = syncMutex.withLock {
        if (notify) _syncing.value = true
        try {
            val newest = db.messageDao().maxCreatedAtMillis(key.owner) ?: 0L
            val now = System.currentTimeMillis()
            val full = forceFull || newest == 0L || now - lastFullSyncMillis >= 15 * 60_000L
            if (full) {
                try { request(key) { api.refreshSession(it) } }
                catch (e: HttpException) {
                    // Permit a gradual rollout against an older server. It keeps
                    // its original fixed token lifetime until the server is updated.
                    if (e.code() != 404 && e.code() != 405) throw e
                }
            }
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
        } finally { if (notify) _syncing.value = false }
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
        // В групповом звонке приглашённых несколько: участие видно по participants.
        val invited = if (call.participants.isEmpty()) call.calleeId == key.owner
        else call.participants.contains(key.owner) && call.callerId != key.owner
        if (!invited || call.status != "ringing") return
        val existing = _activeCall.value
        if (existing != null && existing.callId == call.id) {
            // Экран уже показан из push: дополняем список участников группового звонка.
            val known = peersOf(call, key.owner)
            if (known.isNotEmpty() && known != existing.peers) {
                commit(key) {
                    _activeCall.value = existing.copy(
                        peers = known,
                        peerName = callTitle(known, call.callerId),
                        selfUserId = key.owner,
                        callerUserId = call.callerId,
                    )
                }
            }
            return
        }
        if (_activeCall.value != null) {
            request(key) { api.updateCallStatus(it, call.id, CallStatusRequest("declined")) }
            return
        }
        commit(key) {
            _activeCall.value = ActiveCall(
                callId = call.id,
                peerUserId = call.callerId,
                peerName = callTitle(peersOf(call, key.owner), call.callerId),
                incoming = true,
                ringing = true,
                video = call.video,
                selfUserId = key.owner,
                callerUserId = call.callerId,
                peers = peersOf(call, key.owner),
            )
        }
    }

    /** Участники звонка без себя: с каждым движок поднимает отдельное соединение. */
    private fun peersOf(call: CallDto, owner: String): List<String> {
        val everyone = if (call.participants.isEmpty()) listOf(call.callerId, call.calleeId) else call.participants
        return everyone.filter { it.isNotBlank() && it != owner }.distinct()
    }

    /** Заголовок экрана звонка: имя собеседника, а в группе — «имя и ещё N». */
    private fun callTitle(peers: List<String>, fallback: String): String {
        val first = peers.firstOrNull() ?: fallback
        val name = titleFor(first)
        return if (peers.size > 1) "$name и ещё ${peers.size - 1}" else name
    }

    suspend fun startCall(peerUserId: String, video: Boolean = false) = startCall(listOf(peerUserId), video)

    /**
     * Групповой звонок собирается mesh-схемой на самих телефонах, поэтому
     * собеседников не больше трёх: четверо участников — потолок без
     * серверного микшера. Сервер проверяет это же ограничение.
     */
    suspend fun startCall(peerUserIds: List<String>, video: Boolean = false) = callMutex.withLock {
        val key = key()
        val peers = peerUserIds.filter { it.isNotBlank() }.distinct()
        require(peers.isNotEmpty()) { "Некому звонить" }
        require(peers.none { it == key.owner }) { "Нельзя позвонить самому себе" }
        require(peers.size <= 3) { "В звонке не больше четырёх человек" }
        check(_activeCall.value == null) { "Сначала завершите текущий вызов" }
        val call = request(key) {
            api.initiateCall(it, InitiateCallRequest(peers.first(), peers.drop(1), video))
        }
        commit(key) {
            val everyone = if (call.participants.isEmpty()) peers
            else call.participants.filter { it != key.owner }
            _activeCall.value = ActiveCall(
                callId = call.id,
                peerUserId = everyone.firstOrNull() ?: peers.first(),
                peerName = callTitle(everyone, peers.first()),
                incoming = false,
                ringing = true,
                video = call.video,
                selfUserId = key.owner,
                callerUserId = key.owner,
                peers = everyone,
                // Свой звонок принимать не нужно: гудок снимет ответ собеседника.
                accepted = true,
            )
        }
    }

    suspend fun setCallStatus(status: String) = callMutex.withLock {
        val key = key()
        val call = _activeCall.value ?: return@withLock
        val result = request(key) { api.updateCallStatus(it, call.callId, CallStatusRequest(status)) }
        commit(key) {
            if (_activeCall.value?.callId == call.callId) {
                // accepted ставим по своему действию: только этот телефон знает,
                // что человек нажал «Ответить».
                _activeCall.value = if (status == "active" || result.status == "active")
                    call.copy(ringing = false, accepted = true) else null
            }
        }
    }

    /**
     * Статус пришёл от одного участника. Вдвоём это конец разговора,
     * а в группе — выход этого человека: остальные продолжают говорить.
     */
    private fun applyCallStatus(call: ActiveCall, status: String?, from: String): ActiveCall? {
        if (status == "active") {
            // Ответил кто-то другой: пока трубку здесь не взяли, звонок
            // продолжает звонить и микрофон не включается.
            return if (call.accepted) call.copy(ringing = false) else call
        }
        if (from.isEmpty()) return null
        // Звонящий отменил вызов до ответа — гасим экран входящего.
        if (!call.accepted && from == call.callerUserId) return null
        val rest = call.peers.filter { it != from }
        // Вдвоём выход собеседника — конец разговора, а в группе это
        // минус один участник: остальные продолжают говорить.
        if (rest.isEmpty() || (!call.group && call.accepted)) return null
        return call.copy(peers = rest, peerUserId = rest.first(), peerName = callTitle(rest, rest.first()))
    }

    /**
     * Отправка SDP/ICE конкретному участнику.
     *
     * Без callMutex: ICE-кандидатов много, и они не должны ждать смену статуса.
     */
    suspend fun sendCallSignal(callId: String, to: String, kind: String, payload: JsonObject) {
        val key = key()
        request(key) { api.callSignal(it, callId, CallSignalRequest(to, kind, payload)) }
    }

    // Кэш ICE-серверов: учётка TURN временная, держим её чуть меньше срока.
    @Volatile private var iceCache: Pair<List<IceServerDto>, Long>? = null

    /**
     * ICE-серверы для звонка: STUN и временная учётка TURN.
     *
     * Секрет TURN остаётся на сервере: в приложение попадает логин и пароль
     * на один час, так что из APK утекать нечему.
     */
    suspend fun iceServers(): List<IceServerDto> {
        iceCache?.let { (servers, expiresAt) ->
            if (System.currentTimeMillis() < expiresAt) return servers
        }
        val key = key()
        val config = request(key) { api.iceConfig(it) }
        val ttlMillis = (config.ttl.coerceAtLeast(120) - 60) * 1000L
        iceCache = config.iceServers to (System.currentTimeMillis() + ttlMillis)
        return config.iceServers
    }

    fun dismissCallLocally() { _activeCall.value = null }

    // ---------- Push-уведомления ----------

    /** Последний токен FCM, отданный серверу: нужен, чтобы отвязать при выходе. */
    @Volatile private var pushToken: String? = null

    /**
     * Регистрирует токен устройства. Вызов идемпотентен: сервер перезапишет
     * запись, поэтому его можно звать при каждом запуске.
     */
    suspend fun registerPushToken(token: String) {
        if (token.isBlank() || pushToken == token) return
        val key = key()
        request(key) { api.registerPushDevice(it, PushDeviceRequest(token)) }
        pushToken = token
    }

    /**
     * Показывает входящий звонок, пришедший push-уведомлением: приложение
     * было закрыто, и событие WebSocket мы пропустили.
     */
    fun showIncomingCallFromPush(callId: String, peerId: String, peerName: String, video: Boolean) {
        if (callId.isBlank() || !session.isLoggedIn()) return
        if (_activeCall.value != null) return
        _activeCall.value = ActiveCall(
            callId = callId,
            peerUserId = peerId,
            peerName = peerName.ifBlank { titleFor(peerId) },
            incoming = true,
            ringing = true,
            video = video,
            selfUserId = session.userId().orEmpty(),
            callerUserId = peerId,
            // Push несёт только звонящего; остальных добавит событие call по WebSocket.
            peers = listOf(peerId),
        )
        scope.launch {
            // Имя собеседника и актуальный статус: звонок мог уже завершиться.
            runCatching { resolveUser(peerId) }
            runCatching { fetchCalls() }
        }
    }

    /** Звонящий бросил трубку, пока телефон звонил: убираем экран входящего. */
    fun dismissCallFromPush(callId: String) {
        if (callId.isNotBlank() && _activeCall.value?.callId == callId) _activeCall.value = null
    }

    /**
     * Отклонение кнопкой в уведомлении. Отдельный метод, потому что
     * [setCallStatus] работает с вызовом в памяти, а здесь процесс запустился
     * ради одного нажатия.
     */
    suspend fun declineCall(callId: String) {
        if (callId.isBlank()) return
        val key = key()
        request(key) { api.updateCallStatus(it, callId, CallStatusRequest("declined")) }
        commit(key) { if (_activeCall.value?.callId == callId) _activeCall.value = null }
    }

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
            CallUi(
                c.id, peer, titleFor(peer), c.callerId != key.owner, c.status,
                Instant.parse(c.createdAt).toEpochMilli(), c.video,
            )
        }.sortedByDescending { it.createdAtMillis }
        commit(key) {
            _calls.value = result
            _activeCall.value?.let { active ->
                remote.firstOrNull { it.id == active.callId }?.let { c ->
                    _activeCall.value = when {
                        c.status == "ringing" -> active
                        // Общий статус записи — не состояние этого телефона:
                        // «active» выставляет и ответ другого участника.
                        c.status == "active" -> if (active.accepted) active.copy(ringing = false) else active
                        // Закрытая запись из-за выхода одного участника не должна
                        // ронять групповой разговор у остальных (старые серверы).
                        active.accepted && active.group -> active
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
            val remote = if (e.ciphertext.isBlank()) null else MessageCodec.voice(e.ciphertext)
            // Пока запись не загружена на сервер, конверта ещё нет: играем локальный файл.
            val voice = when {
                remote != null -> VoiceMessage(
                    remote.id, e.localMediaPath,
                    if (remote.durationMs > 0) remote.durationMs else e.localMediaDurationMs, remote.size,
                )
                e.localMediaPath != null && e.localMediaMime?.startsWith("audio/") == true ->
                    VoiceMessage(null, e.localMediaPath, e.localMediaDurationMs, 0)
                else -> null
            }
            val attachment = if (voice != null) null else uiAttachment(e)
            val text = when {
                voice != null -> MessageCodec.voiceLabel(voice.durationMs)
                attachment != null -> attachmentLabel(attachment)
                else -> MessageCodec.plainText(e.ciphertext)
            }
            UiMessage(e.id, e.senderId, text, e.createdAtMillis,
                e.senderId == owner && e.ownerId == owner, e.deliveryState == "failed", e.deliveryState == "pending",
                e.error, e.clientId?.let { "${e.senderId}:$it" } ?: e.id, voice, attachment)
        } }.flowOn(Dispatchers.IO)
    }

    /**
     * Вложение строки: сначала конверт с сервера, иначе локальная копия
     * из очереди отправки — её видно сразу, ещё до загрузки.
     */
    private fun uiAttachment(e: MessageEntity): UiAttachment? {
        val remote = if (e.ciphertext.isBlank()) null else MessageCodec.attachment(e.ciphertext)
        if (remote != null) {
            val kind = remote.first
            val media = remote.second
            val local = e.localMediaPath ?: mediaCacheFile(media.id).takeIf { it.isFile }?.absolutePath
            return UiAttachment(
                kind, media.id, local,
                media.name?.takeIf { it.isNotBlank() } ?: defaultAttachmentName(kind),
                media.mime, media.size, media.durationMs,
                if (media.width > 0) media.width else e.localMediaWidth,
                if (media.height > 0) media.height else e.localMediaHeight,
            )
        }
        val kind = e.localMediaKind ?: return null
        val path = e.localMediaPath ?: return null
        if (kind !in ATTACHMENT_KINDS) return null
        return UiAttachment(
            kind, null, path,
            e.localMediaName?.takeIf { it.isNotBlank() } ?: defaultAttachmentName(kind),
            e.localMediaMime ?: Attachments.DEFAULT_MIME,
            e.localMediaSize, e.localMediaDurationMs, e.localMediaWidth, e.localMediaHeight,
        )
    }

    private fun attachmentLabel(a: UiAttachment): String = when (a.kind) {
        MessageContent.KIND_IMAGE -> "Фото"
        MessageContent.KIND_VIDEO -> "Видео"
        else -> a.name
    }

    private fun defaultAttachmentName(kind: String): String = when (kind) {
        MessageContent.KIND_IMAGE -> "Фото"
        MessageContent.KIND_VIDEO -> "Видео"
        else -> "Файл"
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

    /**
     * Голосовое сообщение уходит той же очередью, что и текст: строка появляется
     * сразу со статусом «Ожидает отправки», файл записи переносится в приватный
     * каталог приложения, а загрузка в /v1/media и отправка конверта происходят
     * �� flushOutbox — с теми же повторами и сохранением порядка.
     */
    suspend fun sendVoice(chatId: String, recording: VoiceRecording) {
        val key = key()
        require(recording.durationMs >= VoiceRecorder.MIN_DURATION_MS) { "Слишком короткая запись. Запишите чуть дольше." }
        require(recording.durationMs <= VoiceRecorder.MAX_DURATION_MS + 1_000) { "Запись длиннее 5 минут. Запишите короче." }
        val size = withContext(Dispatchers.IO) { recording.file.length() }
        require(size > 0) { "Запись не удалась. Попробуйте ещё раз." }
        require(size <= MAX_VOICE_BYTES) { "Голосовое сообщение слишком большое. Запишите короче." }
        val id = UUID.randomUUID().toString()
        // Кэш записи может быть вычищен системой, а очередь ждёт сеть сколько нужно.
        val stored = withContext(Dispatchers.IO) { moveTo(recording.file, File(outboxDir(), "$id.m4a")) }
        try {
            commit(key) {
                val chat = requireNotNull(db.chatDao().get(chatId)) { "Сначала откройте диалог" }
                check(chat.type != "unavailable") { "Доступ к группе прекращён. Обратитесь к её владельцу." }
                val now = Instant.now()
                db.messageDao().upsert(MessageEntity(
                    "local:$id", key.owner, if (chat.type == "dm") chatId else "", chatId,
                    "", now.toString(), null,
                    ownerId = key.owner, createdAtMillis = now.toEpochMilli(), deliveryState = "pending", clientId = id,
                    localMediaPath = stored.absolutePath, localMediaMime = recording.mime,
                    localMediaDurationMs = recording.durationMs,
                ))
            }
        } catch (e: Throwable) {
            // Строки в базе нет — файл записи тоже не должен остаться мусором.
            withContext(NonCancellable + Dispatchers.IO) { stored.delete() }
            throw e
        }
        requestOutbox()
    }

    /**
     * Фото, видео и файлы уходят той же очередью, что текст и голосовые:
     * строка появляется сразу, файл копируется в приватный каталог (выданный
     * системой Uri живёт только до перезапуска), а загрузка в /v1/media и
     * отправка конверта происходят в flushOutbox — с повторами и порядком.
     */
    suspend fun sendAttachment(chatId: String, uri: Uri) {
        val key = key()
        val picked = withContext(Dispatchers.IO) {
            Attachments.copyToOutbox(context, uri, attachOutboxDir(), MAX_ATTACHMENT_BYTES)
        }
        try {
            commit(key) {
                val chat = requireNotNull(db.chatDao().get(chatId)) { "Сначала откройте диалог" }
                check(chat.type != "unavailable") { "Доступ к группе прекращён. Обратитесь к её владельцу." }
                val id = UUID.randomUUID().toString()
                val now = Instant.now()
                db.messageDao().upsert(MessageEntity(
                    "local:$id", key.owner, if (chat.type == "dm") chatId else "", chatId,
                    "", now.toString(), null,
                    ownerId = key.owner, createdAtMillis = now.toEpochMilli(), deliveryState = "pending", clientId = id,
                    localMediaPath = picked.file.absolutePath, localMediaMime = picked.mime,
                    localMediaDurationMs = picked.durationMs, localMediaKind = picked.kind,
                    localMediaName = picked.name, localMediaSize = picked.size,
                    localMediaWidth = picked.width, localMediaHeight = picked.height,
                ))
            }
        } catch (e: Throwable) {
            // Строки в базе нет — копия файла тоже не должна остаться мусором.
            withContext(NonCancellable + Dispatchers.IO) { picked.file.delete() }
            throw e
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
                    // Голосовое: сначала загружаем запись в /v1/media, затем отправляем конверт с media.id.
                    val ready = ensureMediaUploaded(key, m)
                    val sent = request(key) { auth ->
                        if (ready.recipientId.isEmpty()) api.sendChatMessage(auth, ready.chatId, SendChatMessageRequest(ready.ciphertext, clientId))
                        else api.sendMessage(auth, SendMessageRequest(ready.recipientId, ready.ciphertext, clientId))
                    }
                    persist(key, sent.copy(clientMessageId = clientId))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val code = (e as? HttpException)?.code()
                    val retryable = e is IOException || code != null && (code >= 500 && code != 507 || code in listOf(408, 429))
                    val mediaUpload = m.ciphertext.isBlank() && m.localMediaPath != null
                    val voiceUpload = mediaUpload && m.localMediaKind == null
                    commit(key) { db.messageDao().get(m.id)?.let { current ->
                        if (current.deliveryState == "pending") db.messageDao().upsert(current.copy(
                            deliveryState = if (retryable) "pending" else "failed",
                            error = if (retryable) null else when {
                                code == 413 -> "Сервер не принял файл: он слишком большой или закончилась квота."
                                code == 507 -> "На сервере нет места для файлов. Сообщите владельцу Umbra."
                                voiceUpload -> "Голосовое сообщение не отправлено. Повторите отправку."
                                mediaUpload -> "Вложение не отправлено. Повторите отправку."
                                else -> "Не отправлено. Проверьте доступ к чату и повторите."
                            },
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

    /** Небольшой кэш миниатюр: список сообщений не перерисует их каждый раз. */
    private val thumbCache = LruCache<String, Bitmap>(24)

    /**
     * Миниатюра фото или кадр видео. Если своей копии нет, файл скачивается
     * один раз и остаётся в кэше медиа.
     */
    suspend fun attachmentThumbnail(a: UiAttachment): Bitmap? {
        if (!Attachments.hasPreview(a.kind)) return null
        val cacheKey = a.mediaId ?: a.localPath ?: return null
        thumbCache.get(cacheKey)?.let { return it }
        val local = a.localPath?.let { File(it) }?.takeIf { it.isFile && it.length() > 0 }
        val file = local ?: a.mediaId?.let { runCatching { attachmentFile(it) }.getOrNull() } ?: return null
        val bitmap = withContext(Dispatchers.IO) { Attachments.thumbnail(file, a.kind) } ?: return null
        thumbCache.put(cacheKey, bitmap)
        return bitmap
    }

    /**
     * Загружает файл (запись, фото, видео, документ) в /v1/media и подставляет
     * в строку готовый конверт. Повторный вызов ничего не делает: признак
     * загруженного файла — непустой ciphertext, поэтому обрыв сети между
     * загрузкой и отправкой не создаёт вторую копию на сервере.
     */
    private suspend fun ensureMediaUploaded(key: SessionKey, m: MessageEntity): MessageEntity {
        if (m.ciphertext.isNotBlank()) return m
        val kind = m.localMediaKind ?: MessageContent.KIND_VOICE
        val voice = kind == MessageContent.KIND_VOICE
        val lost = if (voice) "Запись потеряна. Запишите голосовое сообщение заново."
            else "Файл потерян. Выберите вложение заново."
        val path = m.localMediaPath ?: throw IllegalStateException(lost)
        val file = File(path)
        val size = withContext(Dispatchers.IO) { if (file.isFile) file.length() else 0L }
        if (size <= 0) throw IllegalStateException(lost)
        val mime = m.localMediaMime ?: if (voice) VoiceRecorder.MIME else Attachments.DEFAULT_MIME
        val fileName = m.localMediaName?.takeIf { it.isNotBlank() } ?: if (voice) "voice.m4a" else file.name
        // Неизвестный или битый тип не должен ронять отправку: шлём как двоичный.
        val mediaType = mime.toMediaTypeOrNull() ?: Attachments.DEFAULT_MIME.toMediaType()
        val up = request(key) { auth ->
            val part = MultipartBody.Part.createFormData("file", fileName, file.asRequestBody(mediaType))
            val response = api.uploadMedia(auth, part, mime.toRequestBody("text/plain".toMediaType()))
            if (!response.isSuccessful) throw HttpException(response)
            response.body() ?: throw IOException("Empty upload response")
        }
        check(up.id.isNotBlank()) {
            if (voice) "Сервер не сохранил запись. Повторите отправку." else "Сервер не сохранил файл. Повторите отправку."
        }
        val envelope = MessageCodec.encode(MessageContent(
            kind = kind,
            media = MediaContent(
                id = up.id,
                mime = up.contentType.ifBlank { mime },
                size = if (up.size > 0) up.size else size,
                name = if (voice) null else fileName,
                durationMs = m.localMediaDurationMs,
                width = m.localMediaWidth,
                height = m.localMediaHeight,
            ),
        ))
        // Сначала фиксируем конверт в базе и только потом трогаем файл. Раньше
        // копия уезжала в кэш до записи нового пути, и обрыв между этими шагами
        // оставлял строку со ссылкой на уже несуществующий файл: повторная
        // отправка падала с «Файл потерян».
        val next = m.copy(ciphertext = envelope)
        commit(key) { db.messageDao().upsert(next) }
        // Свою копию оставляем на устройстве как кэш — уже под именем media id.
        val cached = withContext(NonCancellable + Dispatchers.IO) {
            runCatching { copyToCache(file, mediaCacheFile(up.id)) }.getOrNull()
        } ?: return next
        val moved = next.copy(localMediaPath = cached.absolutePath)
        commit(key) { db.messageDao().upsert(moved) }
        // Файл из очереди удаляем последним: до этого он есть сразу в двух местах.
        withContext(NonCancellable + Dispatchers.IO) { runCatching { if (file != cached) file.delete() } }
        return moved
    }

    /** Файл голосового сообщения: из локального кэша либо скачивается с сервера. */
    suspend fun voiceFile(mediaId: String): File =
        mediaFile(mediaId, MAX_VOICE_BYTES, "Запись не найдена на сервере.")

    /** Файл вложения (фото, видео, документ): из кэша либо с сервера. */
    suspend fun attachmentFile(mediaId: String): File =
        mediaFile(mediaId, MAX_ATTACHMENT_BYTES, "Файл не найден на сервере.")

    /** Готовая копия файла на устройстве, если она есть. */
    fun cachedMediaFile(mediaId: String): File? =
        mediaCacheFile(mediaId).takeIf { it.isFile && it.length() > 0 }

    /** Файл вложения на устройстве: своя копия либо скачивание с сервера. */
    suspend fun attachmentLocalFile(a: UiAttachment): File {
        val local = a.localPath?.let { File(it) }?.takeIf { it.isFile && it.length() > 0 }
        if (local != null) return local
        val id = a.mediaId ?: throw IllegalStateException("Файл ещё не отправлен. Дождитесь отправки.")
        return attachmentFile(id)
    }

    /** Сохранение вложения в выбранное человеком место. */
    suspend fun saveAttachmentTo(a: UiAttachment, destination: Uri) {
        val file = attachmentLocalFile(a)
        withContext(Dispatchers.IO) { Attachments.writeTo(context, destination, file) }
    }

    private suspend fun mediaFile(mediaId: String, maxBytes: Long, missing: String): File {
        require(mediaId.isNotBlank()) { "Вложение недоступно" }
        val target = mediaCacheFile(mediaId)
        if (withContext(Dispatchers.IO) { target.isFile && target.length() > 0 }) return target
        // Один файл — одна загрузка. Миниатюра и открытие вложения просят файл
        // одновременно, а раньше оба скачивания писали в общий .part и портили
        // друг другу содержимое кэша.
        return mediaLock(mediaId).withLock { downloadMedia(mediaId, target, maxBytes, missing) }
    }

    private suspend fun downloadMedia(mediaId: String, target: File, maxBytes: Long, missing: String): File {
        // Пока ждали замок, файл мог уже скачать другой запрос.
        if (withContext(Dispatchers.IO) { target.isFile && target.length() > 0 }) return target
        val key = key()
        val file = request(key) { auth ->
            val response = api.downloadMedia(auth, mediaId)
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                if (response.code() == 404) throw IllegalStateException(missing)
                throw HttpException(response)
            }
            val body = response.body() ?: throw IOException("Empty media response")
            withContext(Dispatchers.IO) { body.use { saveMedia(it.byteStream(), target, maxBytes) } }
        }
        withContext(Dispatchers.IO) { runCatching { pruneMediaCache() } }
        return file
    }

    /**
     * Пишем через .part и переименовываем: недокачанный файл не попадёт в кэш.
     * Имя временного файла уникально для каждой загрузки.
     */
    private fun saveMedia(input: InputStream, target: File, maxBytes: Long): File {
        val temp = File(target.parentFile, target.name + "." + UUID.randomUUID().toString().take(8) + ".part")
        try {
            var total = 0L
            temp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    check(total <= maxBytes) { "Файл слишком большой для загрузки." }
                    out.write(buffer, 0, read)
                }
            }
            check(total > 0) { "Сервер вернул пустой файл." }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
        return target
    }

    /** Замки по mediaId: параллельные запросы одного файла ждут первую загрузку. */
    private val mediaLocks = mutableMapOf<String, Mutex>()
    private val mediaLocksGuard = Mutex()

    private suspend fun mediaLock(mediaId: String): Mutex =
        mediaLocksGuard.withLock { mediaLocks.getOrPut(mediaId) { Mutex() } }

    /** Копия в кэш через уникальный .part: обрезанный файл в кэше не появится. */
    private fun copyToCache(source: File, target: File): File {
        if (source == target) return target
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + "." + UUID.randomUUID().toString().take(8) + ".part")
        try {
            source.copyTo(temp, overwrite = true)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
        return target
    }

    private fun mediaCacheDir(): File = File(context.filesDir, MEDIA_CACHE_DIR).apply { mkdirs() }

    private fun mediaCacheFile(mediaId: String): File =
        File(mediaCacheDir(), mediaId.replace(Regex("[^A-Za-z0-9_.-]"), "_"))

    private fun outboxDir(): File = File(context.filesDir, VOICE_OUTBOX_DIR).apply { mkdirs() }

    /**
     * Файл под запись видеосообщения камерой. Лежит в приватном каталоге
     * вложений, поэтому доступен камере через FileProvider (res/xml/file_paths.xml).
     */
    fun videoCaptureFile(): File {
        val dir = File(File(context.filesDir, ATTACH_DIR), "capture").apply { mkdirs() }
        return File(dir, "video_${UUID.randomUUID()}.mp4")
    }

    private fun attachOutboxDir(): File = File(context.filesDir, ATTACH_OUTBOX_DIR).apply { mkdirs() }

    private fun moveTo(source: File, target: File): File {
        if (source == target) return target
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
        return target
    }

    /** Кэш записей не должен расти бесконечно: держим бюджет, удаляя самые старые файлы. */
    private fun pruneMediaCache() {
        // .part — файлы текущих загрузок, их убирает сама загрузка.
        val files = mediaCacheDir().listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MEDIA_CACHE_BUDGET_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            val size = file.length()
            if (file.delete()) total -= size
            if (total <= MEDIA_CACHE_BUDGET_BYTES) break
        }
    }

    /** Смена аккаунта или удаление: локальные файлы записей тоже должны исчезнуть. */
    private fun clearLocalMediaFiles() {
        runCatching { File(context.filesDir, MEDIA_CACHE_DIR).deleteRecursively() }
        runCatching { File(context.filesDir, VOICE_DIR).deleteRecursively() }
        runCatching { File(context.filesDir, ATTACH_DIR).deleteRecursively() }
    }

    suspend fun deleteExpiredMessages() {
        val key = key()
        commit(key) { db.messageDao().deleteExpired(System.currentTimeMillis()) }
    }

    fun close() { stopRealtime(); scope.cancel(); ws.close() }

    companion object {
        const val MEDIA_CACHE_DIR = "media"
        private const val VOICE_DIR = "voice"
        private const val VOICE_OUTBOX_DIR = "voice/outbox"
        private const val ATTACH_DIR = "attach"
        private const val ATTACH_OUTBOX_DIR = "attach/outbox"

        /** 5 минут AAC 64 кбит/с ≈ 2,5 МиБ; запас — на случай другого кодека устройства. */
        private const val MAX_VOICE_BYTES = 24L * 1024 * 1024
        private const val MEDIA_CACHE_BUDGET_BYTES = 256L * 1024 * 1024

        /** Сервер по умолчанию принимает 50 МиБ на файл; оставляем запас на конверт. */
        const val MAX_ATTACHMENT_BYTES = 48L * 1024 * 1024
    }
}
