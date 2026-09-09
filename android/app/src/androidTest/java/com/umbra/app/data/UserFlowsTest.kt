package com.umbra.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.umbra.app.data.api.createUmbraApi
import com.umbra.app.data.db.AppDatabase
import com.umbra.app.data.db.ChatEntity
import com.umbra.app.data.db.MessageEntity
import com.umbra.app.data.msg.MessageCodec
import com.umbra.app.data.msg.MessageContent
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.data.repo.SessionPhase
import com.umbra.app.data.session.SessionStore
import com.umbra.app.data.ws.WebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Run on an emulator/device: real Retrofit serialization, Room and encrypted session preferences. */
@RunWith(AndroidJUnit4::class)
class UserFlowsTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var session: SessionStore
    private lateinit var repo: ChatRepository
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    @Volatile private var handler: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }
    private val me = "owner"
    private val peer = "friend"
    private val created = "2026-09-08T10:00:00.123456789Z"
    private var preferenceName = ""
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() {
        preferenceName = "umbra_flow_test_${UUID.randomUUID()}"
        session = SessionStore(context, preferenceName)
        session.save("token", me, "owner_name", "+79991234567", "Имя", "Фамилия", "avatar-old")
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return handler(request)
            }
        }
        server.start()
        val url = server.url("/").toString()
        repo = ChatRepository(context, createUmbraApi(url), db, session, WebSocketClient(url))
    }

    @After fun tearDown() {
        repo.close()
        db.close()
        session.clear()
        context.deleteSharedPreferences(preferenceName)
        server.shutdown()
    }

    private fun ok(body: String, code: Int = 200) = MockResponse().setResponseCode(code).addHeader("Content-Type", "application/json").setBody(body)
    private fun user(id: String = peer) = """{"id":"$id","username":"friend_name","display_name":"Друг"}"""
    private fun account(id: String = me) = """{"id":"$id","username":"owner_name","phone":"+79991234567","display_name":"Имя","last_name":"Фамилия","avatar_media_id":"avatar-old"}"""
    private fun envelope() = MessageCodec.encode(MessageContent(text = "Привет"))
    private fun message(id: String = "server-id", clientId: String = "request-id", at: String = created) = buildJsonObject {
        put("id", id); put("sender_id", me); put("recipient_id", peer); put("ciphertext", envelope())
        put("created_at", at); put("client_message_id", clientId)
    }
    private suspend fun seedPending() {
        db.chatDao().upsert(ChatEntity(peer, "dm", "Друг"))
        db.messageDao().upsert(MessageEntity("local:request-id", me, peer, peer, envelope(), created, null,
            ownerId = me, createdAtMillis = Instant.parse(created).toEpochMilli(), deliveryState = "pending", clientId = "request-id"))
    }

    @Test fun lostAcknowledgementRetriesSameIdWithoutDuplicate() = runBlocking {
        val attempts = AtomicInteger()
        val first = CountDownLatch(1)
        val clientIds = ConcurrentLinkedQueue<String>()
        handler = { request ->
            if (request.method == "POST" && request.requestUrl?.encodedPath == "/v1/messages") {
                val body = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
                val client = body["client_message_id"]?.jsonPrimitive?.content.orEmpty()
                clientIds.add(client)
                if (attempts.incrementAndGet() == 1) { first.countDown(); ok("""{"error":"response lost after acceptance"}""", 503) }
                else ok(message(clientId = client).toString(), 201)
            } else ok("{}", 404)
        }
        db.chatDao().upsert(ChatEntity(peer, "dm", "Друг"))
        repo.sendText(peer, "Привет")
        assertTrue(withContext(Dispatchers.IO) { first.await(5, TimeUnit.SECONDS) })
        repo.flushOutbox()
        val rows = withTimeout(5000) { repo.messagesFor(peer).first { it.size == 1 && !it[0].pending } }
        assertFalse(rows.single().failed)
        assertTrue(clientIds.all { it.isNotBlank() })
        assertEquals(1, clientIds.toSet().size)
        assertEquals(2, attempts.get())
    }

    @Test fun failedMessageCanBeRetriedWithoutLosingText() = runBlocking {
        val denied = java.util.concurrent.atomic.AtomicBoolean(true)
        handler = { request ->
            val client = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject["client_message_id"]!!.jsonPrimitive.content
            if (denied.get()) ok("""{"error":"forbidden"}""", 403) else ok(message(clientId = client).toString(), 201)
        }
        db.chatDao().upsert(ChatEntity(peer, "dm", "Друг"))
        repo.sendText(peer, "Привет")
        val failed = withTimeout(5000) { repo.messagesFor(peer).first { it.singleOrNull()?.failed == true } }.single()
        assertEquals("Привет", failed.text)
        denied.set(false)
        repo.retryMessage(failed.id)
        // flushOutbox also makes the test independent of the background scheduling order.
        repo.flushOutbox()
        val sent = withTimeout(5000) { repo.messagesFor(peer).first { it.singleOrNull()?.let { m -> !m.pending && !m.failed } == true } }
        assertEquals("Привет", sent.single().text)
    }

    @Test fun reloginPreservesOutboxAndSwitchingAccountClearsIt() = runBlocking {
        seedPending()
        handler = { ok("""{"token":"new-token","account":${account()},"profile_complete":true}""") }
        repo.verifyCode("8 (999) 123-45-67", "123456")
        assertNotNull(db.messageDao().get("local:request-id"))
        handler = { ok("""{"token":"another-token","account":${account("other-owner")},"profile_complete":true}""") }
        repo.verifyCode("+79991234568", "654321")
        assertNull(db.messageDao().get("local:request-id"))
        assertNull(db.chatDao().get(peer))
    }

    @Test fun failedDeletionKeepsSessionAndHistory() = runBlocking {
        seedPending()
        handler = { ok("""{"error":"unavailable"}""", 503) }
        val failure = runCatching { repo.deleteAccount() }.exceptionOrNull()
        assertTrue(failure is HttpException)
        assertEquals(SessionPhase.READY, repo.phase.value)
        assertEquals("token", session.token())
        assertNotNull(db.messageDao().get("local:request-id"))
    }

    @Test fun profileEditRetainsAvatarAndAllowsClearingLastName() = runBlocking {
        handler = { ok("""{"id":"owner","username":"new_name","display_name":"Новое имя","last_name":""}""") }
        repo.updateProfile("Новое имя", "", "@new_name")
        assertEquals("avatar-old", repo.account.value.avatarMediaId)
        assertEquals("", repo.account.value.lastName)
        val body = Json.parseToJsonElement(requests.single().body.clone().readUtf8()).jsonObject
        assertEquals("new_name", body["username"]!!.jsonPrimitive.content)
        assertEquals("", body["last_name"]!!.jsonPrimitive.content)
    }

    @Test fun newlyOpenedEmptyDmAppearsInChatList() = runBlocking {
        handler = { ok(user()) }
        repo.openDm(peer)
        assertEquals(peer, repo.conversations().first { it.isNotEmpty() }.single().chatId)
        assertEquals("Друг", repo.conversations().first().single().title)
    }

    @Test fun networkFailureIsNotReportedAsUserNotFound() = runBlocking {
        handler = { ok("""{"error":"unavailable"}""", 503) }
        assertTrue(runCatching { repo.resolveByUsername("friend_name") }.exceptionOrNull() is HttpException)
        handler = { ok("""{"error":"user not found"}""", 404) }
        assertNull(repo.resolveByUsername("friend_name"))
    }

    @Test fun statusOnlyCallResponseAllowsAcceptThenEnd() = runBlocking {
        handler = { request -> when (request.requestUrl?.encodedPath) {
            "/v1/calls" -> ok("""{"id":"call-id","caller_id":"owner","callee_id":"friend","status":"ringing","created_at":"$created"}""", 201)
            "/v1/calls/call-id/status" -> ok(request.body.clone().readUtf8())
            else -> ok("{}", 404)
        } }
        repo.startCall(peer)
        repo.setCallStatus("active")
        assertEquals(false, repo.activeCall.value?.ringing)
        repo.setCallStatus("ended")
        assertNull(repo.activeCall.value)
    }

    @Test fun syncRestoresGroupsAndUsesExactPageCursor() = runBlocking {
        val pageOne = (0 until 200).map { index -> message("m$index", "c$index", "2026-09-08T10:00:00.${index.toString().padStart(9, '0')}Z") }
        handler = { request -> when (request.requestUrl?.encodedPath) {
            "/v1/chats" -> ok("""{"chats":[{"id":"group-id","type":"group","title":"Семья"}]}""")
            "/v1/account" -> ok(account())
            "/v1/users/friend" -> ok(user())
            "/v1/messages" -> {
                val after = request.requestUrl!!.queryParameter("after_id")
                if (after == null) ok(buildJsonObject { put("messages", JsonArray(pageOne)) }.toString())
                else if (after == "m199" && request.requestUrl!!.queryParameter("since") == "2026-09-08T10:00:00.000000199Z") ok("""{"messages":[]}""")
                else ok("""{"error":"incorrect cursor"}""", 400)
            }
            else -> ok("{}", 404)
        } }
        repo.refresh(forceFull = true)
        assertEquals("Семья", db.chatDao().get("group-id")?.title)
        assertEquals(200, repo.messagesFor(peer).first().size)
    }

    @Test fun restAcknowledgementReconcilesPendingMessage() = runBlocking {
        seedPending()
        handler = { request -> when (request.requestUrl?.encodedPath) {
            "/v1/chats" -> ok("""{"chats":[]}""")
            "/v1/account" -> ok(account())
            "/v1/users/friend" -> ok(user())
            "/v1/messages" -> if (request.method == "GET") ok(buildJsonObject { put("messages", JsonArray(listOf(message()))) }.toString()) else ok("{}", 500)
            else -> ok("{}", 404)
        } }
        repo.refresh(forceFull = true)
        assertNull(db.messageDao().get("local:request-id"))
        assertEquals("server-id", repo.messagesFor(peer).first().single().id)
        assertTrue(requests.none { it.method == "POST" })
    }
}
