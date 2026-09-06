package com.umbra.app.data.repo

import com.umbra.app.crypto.CryptoManager
import com.umbra.app.data.api.MessageDto
import com.umbra.app.data.api.SendMessageRequest
import com.umbra.app.data.api.UmbraApi
import com.umbra.app.data.db.AppDatabase
import com.umbra.app.data.db.ChatEntity
import com.umbra.app.data.db.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Единая точка доступа к данным: объединяет API, Room и криптографию.
 * Расшифровка выполняется в памяти при чтении; в БД — только ciphertext.
 */
class ChatRepository(
    private val api: UmbraApi,
    private val db: AppDatabase,
    private val crypto: CryptoManager,
) {
    private fun auth() = "Bearer ${requireNotNull(token())}"

    private fun token() = crypto.currentToken()

    // ---------- аутентификация / регистрация ----------

    suspend fun register(username: String) {
        crypto.generateAndPersistIdentity()
        val resp = api.register(crypto.buildRegisterRequest(username))
        crypto.saveUser(username, resp.id)
    }

    suspend fun login(username: String) {
        val challenge = api.challenge(com.umbra.app.data.api.ChallengeRequest(username)).challenge
        val signature = crypto.signChallenge(challenge)
        val verify = api.verify(
            com.umbra.app.data.api.VerifyRequest(username, challenge, signature)
        )
        crypto.saveSession(verify.token)
    }

    fun isLoggedIn(): Boolean = token() != null

    fun logout() = crypto.clearSession()

    // ---------- сообщения ----------

    suspend fun sendMessage(recipientId: String, plaintext: String, expiresIn: Long? = null) {
        // Убеждаемся, что сессия установлена (если нет — получим prekeys и установим).
        ensureSession(recipientId)
        val ciphertext = crypto.encrypt(recipientId, plaintext)
        val dto = api.sendMessage(auth(), SendMessageRequest(recipientId, ciphertext, expiresIn))
        persist(dto, decrypted = plaintext)
    }

    suspend fun sendChatMessage(chatId: String, plaintext: String, expiresIn: Long? = null) {
        // Групповые ключи (Sender Keys) на клиенте; здесь отправляем в чат.
        // В MVP групповое шифрование — отдельная задача; ciphertext отправителя.
        val dto = api.sendChatMessage(
            auth(), chatId,
            com.umbra.app.data.api.SendChatMessageRequest(ciphertext = crypto.encrypt(chatId, plaintext), expires_in = expiresIn),
        )
        persist(dto, decrypted = plaintext)
    }

    private suspend fun ensureSession(recipientId: String) {
        val username = /* здесь нужно username получателя; для DM используем id */
            recipientId
        runCatching { api.prekeys(username) }.getOrNull()?.let { bundle ->
            crypto.establishSession(recipientId, bundle)
        }
    }

    fun messagesFor(chatId: String): Flow<List<MessageEntity>> =
        db.messageDao().messagesFor(chatId).map { list ->
            // Расшифровка «на лету»: возвращаем сущности с заполненным decryptedBody.
            list.map { e ->
                if (e.decryptedBody == null) {
                    runCatching {
                        e.copy(decryptedBody = crypto.decrypt(e.senderId, e.ciphertext))
                    }.getOrDefault(e)
                } else e
            }
        }

    fun chats(): Flow<List<ChatEntity>> = db.chatDao().all()

    suspend fun refreshChats() {
        api.chats(auth()).chats.forEach { c ->
            db.chatDao().upsert(ChatEntity(c.id, c.type, c.title))
        }
    }

    // ---------- вспомогательное ----------

    private suspend fun persist(dto: MessageDto, decrypted: String? = null) {
        db.messageDao().upsert(
            MessageEntity(
                id = dto.id,
                senderId = dto.sender_id,
                recipientId = dto.recipient_id,
                chatId = dto.chat_id.ifEmpty { dto.recipient_id },
                ciphertext = dto.ciphertext,
                createdAt = dto.created_at,
                expiresAt = dto.expires_at,
                decryptedBody = decrypted,
            )
        )
    }
}
