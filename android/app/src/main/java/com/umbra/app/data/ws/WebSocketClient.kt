package com.umbra.app.data.ws

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * WebSocket-клиент для realtime-событий сервера (входящие сообщения, сигналинг звонков).
 * Соединение: wss://host/v1/ws?token=...
 */
class WebSocketClient(private val baseUrl: String) {

    private val client = OkHttpClient()
    private var socket: WebSocket? = null
    private val events = Channel<Event>(Channel.BUFFERED)

    private val json = Json { ignoreUnknownKeys = true }

    /** Событие от сервера (тип + сырые данные JSON). */
    data class Event(val type: String, val data: JsonObject)

    val eventFlow: Flow<Event> = events.receiveAsFlow()

    fun connect(token: String) {
        disconnect()
        val url = baseUrl.replace("http", "ws") + "/v1/ws?token=$token"
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, listener)
    }

    fun disconnect() {
        socket?.close(1000, "client disconnect")
        socket = null
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val obj = json.parseToJsonElement(text).jsonObject
                val type = obj["type"]?.jsonPrimitive?.content ?: return
                val data = obj["data"]?.jsonObject ?: JsonObject(emptyMap())
                events.trySend(Event(type, data))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Переподключение выполняет ViewModel/repository (экспоненциальный backoff).
        }
    }
}
