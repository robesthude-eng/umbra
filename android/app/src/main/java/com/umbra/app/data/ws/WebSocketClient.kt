package com.umbra.app.data.ws

import com.umbra.app.data.diag.DiagLog
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
import kotlin.random.Random

/** Reconnect с backoff; токен передаётся в заголовке, не в URL. */
class WebSocketClient(private val baseUrl: String) {
    private val client = OkHttpClient.Builder().pingInterval(25, java.util.concurrent.TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<Event>(128)
    private var job: Job? = null
    @Volatile private var activeToken: String? = null
    private val connectedState = MutableStateFlow(false)
    val connected = connectedState.asStateFlow()
    data class Diagnostics(
        val connected: Boolean = false,
        val attempt: Int = 0,
        val retryInMs: Long = 0,
        val httpCode: Int? = null,
        val lastFailure: String? = null,
        val lastConnectedAtMillis: Long? = null,
    )
    private val diagnosticsState = MutableStateFlow(Diagnostics())
    val diagnostics = diagnosticsState.asStateFlow()
    val eventFlow = events.receiveAsFlow()
    data class Event(val type: String, val data: JsonObject, val token: String)

    @Synchronized
    fun connect(token: String) {
        if (job?.isActive == true && activeToken == token) return
        disconnect()
        activeToken = token
        diagnosticsState.value = Diagnostics()
        job = scope.launch {
            var waitMs = 1000L
            var attempt = 0
            while (isActive) {
                attempt++
                diagnosticsState.value = diagnosticsState.value.copy(attempt = attempt, retryInMs = 0)
                try {
                    connection(token)
                    waitMs = 1000
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val label = failureLabel(e, null)
                    diagnosticsState.value = diagnosticsState.value.copy(
                        connected = false,
                        lastFailure = diagnosticsState.value.lastFailure ?: label,
                    )
                    DiagLog.log("ws-connect", e, "attempt=$attempt retryMs=$waitMs")
                }
                if (activeToken == token) {
                    connectedState.value = false
                    val jitter = Random.nextLong(0, (waitMs / 4).coerceAtLeast(2L))
                    val retryDelay = waitMs + jitter
                    diagnosticsState.value = diagnosticsState.value.copy(connected = false, retryInMs = retryDelay)
                    delay(retryDelay)
                }
                waitMs = (waitMs * 2).coerceAtMost(30_000)
            }
        }
    }

    /** Немедленно пересоздаёт маршрут после смены Wi-Fi, VPN или мобильной сети. */
    @Synchronized
    fun reconnect(token: String) {
        disconnect()
        connect(token)
    }

    @Synchronized
    fun disconnect() {
        job?.cancel()
        job = null
        activeToken = null
        connectedState.value = false
        diagnosticsState.value = Diagnostics()
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
                diagnosticsState.value = Diagnostics(
                    connected = true,
                    attempt = diagnosticsState.value.attempt,
                    lastConnectedAtMillis = System.currentTimeMillis(),
                )
                DiagLog.log("ws-open", note = "protocol=${response.protocol}")
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
                val safeReason = reason.take(100)
                diagnosticsState.value = diagnosticsState.value.copy(
                    connected = false,
                    lastFailure = "WebSocket закрыт: код $code${if (safeReason.isBlank()) "" else " ($safeReason)"}",
                )
                DiagLog.log("ws-closed", note = "code=$code reason=$safeReason")
                if (continuation.isActive) continuation.resume(Unit)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                diagnosticsState.value = diagnosticsState.value.copy(
                    connected = false,
                    httpCode = code,
                    lastFailure = failureLabel(t, code),
                )
                DiagLog.log("ws-failure", t, "http=${code ?: "none"}")
                if (continuation.isActive) continuation.resumeWithException(t)
            }
        })
        continuation.invokeOnCancellation { socket.cancel() }
    }

    private fun failureLabel(error: Throwable, httpCode: Int?): String = when {
        httpCode == 401 -> "WebSocket: сессия отклонена (HTTP 401)"
        httpCode == 403 -> "WebSocket: доступ запрещён (HTTP 403)"
        httpCode == 429 -> "WebSocket: лимит подключений (HTTP 429)"
        httpCode != null -> "WebSocket: сервер ответил HTTP $httpCode"
        error.javaClass.simpleName.contains("UnknownHost", true) -> "WebSocket: ошибка DNS"
        error.javaClass.simpleName.contains("Timeout", true) -> "WebSocket: тайм-аут"
        error.javaClass.simpleName.contains("SSL", true) -> "WebSocket: ошибка TLS"
        else -> "WebSocket: ${error.javaClass.simpleName}"
    }
}
