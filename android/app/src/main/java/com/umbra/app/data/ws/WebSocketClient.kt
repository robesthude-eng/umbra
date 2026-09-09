package com.umbra.app.data.ws

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reconnect с backoff; токен передаётся в заголовке, не в URL. */
class WebSocketClient(private val baseUrl: String) {
    private val client = OkHttpClient.Builder().pingInterval(25, java.util.concurrent.TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<Event>(128)
    private var job: Job? = null
    @Volatile private var activeToken: String? = null
    private val connectedState = MutableStateFlow(false)
    val connected = connectedState.asStateFlow()
    val eventFlow = events.receiveAsFlow()
    data class Event(val type: String, val data: JsonObject, val token: String)

    @Synchronized
    fun connect(token: String) {
        if (job?.isActive == true && activeToken == token) return
        disconnect()
        activeToken = token
        job = scope.launch {
            var waitMs = 1000L
            while (isActive) {
                try {
                    connection(token)
                    waitMs = 1000
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* REST восстановит пропущенное. */ }
                if (activeToken == token) connectedState.value = false
                delay(waitMs)
                waitMs = (waitMs * 2).coerceAtMost(30_000)
            }
        }
    }

    @Synchronized
    fun disconnect() {
        job?.cancel()
        job = null
        activeToken = null
        connectedState.value = false
    }

    fun close() {
        disconnect()
        scope.cancel()
        events.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private suspend fun connection(token: String): Unit = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(requireNotNull(baseUrl.toHttpUrl().resolve("/v1/ws")))
            .header("Authorization", "Bearer " + token).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!continuation.isActive || activeToken != token) { webSocket.cancel(); return }
                connectedState.value = true
                if (events.trySend(Event("connected", JsonObject(emptyMap()), token)).isFailure) webSocket.cancel()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!continuation.isActive || activeToken != token) return
                try {
                    val obj = Json.parseToJsonElement(text).jsonObject
                    val event = Event(obj.getValue("type").jsonPrimitive.content, obj.getValue("data").jsonObject, token)
                    if (events.trySend(event).isFailure) webSocket.cancel()
                } catch (_: Exception) { webSocket.cancel() }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (continuation.isActive) continuation.resume(Unit)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (continuation.isActive) continuation.resumeWithException(t)
            }
        })
        continuation.invokeOnCancellation { socket.cancel() }
    }
}
