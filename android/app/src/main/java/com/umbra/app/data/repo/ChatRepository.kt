package com.umbra.app.data.repo

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
import com.umbra.app.data.ws.WebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/** UI-модель сообщения (с уже расшифрованным текстом; в БД его нет). */
data class UiMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val createdAt: String,
    val expiresAt: String?,
    val outgoing: Boolean,
)

/**
 * Единая точка доступа к данным: объединяет API, Room, WebSocket и криптографию.
 *
 * Приватность: в Room — ТОЛЬКО ciphertext. Открытый текст существует лишь в памяти:
 *  - входящие расшифровываются «на лету» при чтении;
 *  - собственные отправленные хранятся во временном кэше [sentCache] (не в БД).
 */
class ChatRepository(
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
                val text = if (outgoing) {
                    sentCache[e.id] ?: "[сообщение недоступно после перезапуска]"
                } else {
                    runCatching { crypto.decrypt(e.senderId, e.ciphertext) }
                        .getOrDefault("[не удалось расшифровать]")
                }
                UiMessage(
                    id = e.id,
                    senderId = e.senderId,
                    text = text,
                    createdAt = e.createdAt,
                    expiresAt = e.expiresAt,
                    outgoing = outgoing,
                )
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

    // ---------- вспомогательное ----------

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
}
