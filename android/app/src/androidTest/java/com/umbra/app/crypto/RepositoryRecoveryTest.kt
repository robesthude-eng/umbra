package com.umbra.app.crypto

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.umbra.app.data.api.*
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.data.ws.WebSocketClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class RepositoryRecoveryTest {
    @Test
    fun lostAcknowledgementRestartAndIncomingConversation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CryptoPersistenceTest.Profile(context).use { alice -> CryptoPersistenceTest.Profile(context).use { bob ->
            val bobKeys = bob.db.withTransaction {
                bob.crypto.buildRegisterRequest("bob").also { bob.crypto.markKeysPublished() }
            }
            alice.db.withTransaction { alice.crypto.buildRegisterRequest("alice"); alice.crypto.markKeysPublished() }
            for ((profile, id) in listOf(alice to "alice", bob to "bob")) {
                profile.crypto.saveUser(id, id)
                profile.crypto.saveSession("token-" + id, "2099-01-01T00:00:00Z")
            }
            val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
            val requests = CopyOnWriteArrayList<SendMessageRequest>()
            val saved = ConcurrentHashMap<String, MessageDto>()
            val loseAcknowledgement = AtomicBoolean(true)
            val acceptedWithoutAck = AtomicBoolean(false)
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    val user = request.getHeader("Authorization").orEmpty().removePrefix("Bearer token-")
                    fun response(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)
                    return when {
                        path == "/v1/ws" -> MockResponse().setResponseCode(503)
                        path == "/v1/account" -> response(json.encodeToString(AccountResponse(user, user, key_version = 2)))
                        path == "/v1/chats" -> response(json.encodeToString(ChatsResponse(emptyList())))
                        path == "/v1/users/bob/prekeys" -> response(json.encodeToString(PreKeyBundle(
                            "bob", "bob", bobKeys.identity_ed25519, bobKeys.identity_x25519, bobKeys.signed_prekey,
                            bobKeys.signed_prekey_signature, bobKeys.one_time_prekeys.first(), 2, bobKeys.registration_id,
                            bobKeys.signed_prekey_id, bobKeys.one_time_prekey_ids.first(), 1)))
                        path == "/v1/messages" && request.method == "POST" -> {
                            val send = json.decodeFromString<SendMessageRequest>(request.body.readUtf8())
                            requests.add(send)
                            val message = saved.getOrPut(user + ":" + send.client_message_id) {
                                MessageDto("server-" + send.client_message_id, user, send.recipient_id,
                                    ciphertext = send.ciphertext, created_at = Instant.now().toString(),
                                    client_message_id = send.client_message_id)
                            }
                            if (loseAcknowledgement.get()) {
                                acceptedWithoutAck.set(true)
                                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                            } else response(json.encodeToString(message))
                        }
                        path == "/v1/messages" -> response(json.encodeToString(MessagesResponse(
                            saved.values.filter { it.recipient_id == user }.sortedBy { it.created_at })))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val url = server.url("/").toString()
            fun repository(profile: CryptoPersistenceTest.Profile) =
                ChatRepository(context, createUmbraApi(url), profile.db, profile.crypto, WebSocketClient(url))
            var sender = repository(alice)
            var receiver: ChatRepository? = null
            try {
                sender.startChat("bob")
                sender.sendMessage("bob", "persistent outgoing text")
                withTimeout(15_000) {
                    while (!acceptedWithoutAck.get()) kotlinx.coroutines.delay(20)
                }
                sender.close()
                alice.restart()
                sender = repository(alice)
                val pending = withTimeout(5000) { sender.messagesFor("bob").first { it.isNotEmpty() } }
                assertEquals("persistent outgoing text", pending.single().text)
                assertEquals("pending", pending.single().deliveryState)
                loseAcknowledgement.set(false)
                sender.connectRealtime()
                val sent = withTimeout(15_000) { sender.messagesFor("bob").first { it.size == 1 && it[0].deliveryState == "sent" } }
                assertEquals("persistent outgoing text", sent.single().text)
                assertTrue(requests.size >= 2)
                assertEquals(1, requests.map { it.client_message_id }.toSet().size)
                assertEquals(1, requests.map { it.ciphertext }.toSet().size)
                val bobRepository = repository(bob)
                receiver = bobRepository
                bobRepository.connectRealtime()
                val incoming = withTimeout(15_000) { bobRepository.messagesFor("alice").first { it.isNotEmpty() } }
                assertEquals("persistent outgoing text", incoming.single().text)
                assertFalse(incoming.single().outgoing)
                // Повторное чтение истории не вызывает повторный decrypt Signal.
                assertEquals(incoming, bobRepository.messagesFor("alice").first())
                bobRepository.sendMessage("alice", "reply")
                val conversation = withTimeout(15_000) { sender.messagesFor("bob").first { it.size == 2 } }
                assertEquals(setOf("persistent outgoing text", "reply"), conversation.map { it.text }.toSet())
            } finally {
                sender.close()
                receiver?.close()
                server.shutdown()
            }
        } }
    }
}
