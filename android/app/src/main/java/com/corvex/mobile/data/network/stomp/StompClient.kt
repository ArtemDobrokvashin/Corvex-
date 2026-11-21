package com.corvex.mobile.data.network.stomp

import com.corvex.mobile.data.security.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

data class StompFrame(
    val command: String,
    val headers: Map<String, String>,
    val body: String
)

object StompFrameParser {
    fun parse(raw: String): StompFrame? {
        if (raw == "\n" || raw == "\r\n") return null
        val cleaned = raw.trimEnd('\u0000')
        val splitIdx = cleaned.indexOf("\n\n")
        val headerSection = if (splitIdx >= 0) cleaned.substring(0, splitIdx) else cleaned
        val body = if (splitIdx >= 0) cleaned.substring(splitIdx + 2) else ""
        val lines = headerSection.split("\n")
        if (lines.isEmpty()) return null
        val command = lines[0].trim()
        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                headers[lines[i].substring(0, idx).trim()] = lines[i].substring(idx + 1).trim()
            }
        }
        return StompFrame(command, headers, body)
    }
}

@Singleton
class StompClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val tokenStore: TokenStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var isConnected = false
    private var shouldReconnect = false
    private var reconnectAttempt = 0
    private val pendingSubscriptions = mutableListOf<Pair<String, String>>()
    private var messageListener: ((StompFrame) -> Unit)? = null
    private var errorListener: ((Throwable) -> Unit)? = null

    fun connect(onFrame: (StompFrame) -> Unit, onError: (Throwable) -> Unit) {
        messageListener = onFrame
        errorListener = onError
        shouldReconnect = true
        openSocket()
    }

    private fun openSocket() {
        val baseUrl = tokenStore.serverUrl?.trimEnd('/') ?: return
        val wsUrl = baseUrl.replace("https://", "wss://").replace("http://", "ws://") + "/ws/chat/websocket"
        val request = Request.Builder().url(wsUrl).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val token = tokenStore.accessToken ?: ""
                val connectFrame = buildString {
                    append("CONNECT\n")
                    append("accept-version:1.2\n")
                    append("heart-beat:10000,10000\n")
                    if (token.isNotBlank()) {
                        append("Authorization:Bearer $token\n")
                    }
                    append("\n\u0000")
                }
                webSocket.send(connectFrame)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = StompFrameParser.parse(text) ?: return
                if (frame.command == "CONNECTED") {
                    isConnected = true
                    reconnectAttempt = 0
                    startHeartbeat()
                    synchronized(pendingSubscriptions) {
                        pendingSubscriptions.forEach { (dest, id) -> sendSubscribe(dest, id) }
                        pendingSubscriptions.clear()
                    }
                } else {
                    messageListener?.invoke(frame)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                heartbeatJob?.cancel()
                errorListener?.invoke(t)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                heartbeatJob?.cancel()
                if (shouldReconnect) scheduleReconnect()
            }
        })
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && isConnected) {
                delay(10_000L)
                webSocket?.send("\n")
            }
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        reconnectJob?.cancel()
        val delayMs = min(30_000L, 1_000L * (1L shl min(reconnectAttempt, 5)))
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            if (shouldReconnect) openSocket()
        }
    }

    fun subscribeRoom(roomId: String, subscriptionId: String = "sub-$roomId") {
        val destination = "/topic/rooms.$roomId"
        if (isConnected) {
            sendSubscribe(destination, subscriptionId)
        } else {
            synchronized(pendingSubscriptions) {
                pendingSubscriptions.add(destination to subscriptionId)
            }
        }
    }

    private fun sendSubscribe(destination: String, id: String) {
        val frame = "SUBSCRIBE\nid:$id\ndestination:$destination\n\n\u0000"
        webSocket?.send(frame)
    }

    fun sendRoomMessage(roomId: String, content: String, clientMessageId: String = UUID.randomUUID().toString()) {
        val body = """{"content":${escapeJson(content)},"clientMessageId":"$clientMessageId"}"""
        val frame = "SEND\ndestination:/app/rooms/$roomId/send\ncontent-type:application/json\n\n$body\u0000"
        webSocket?.send(frame)
    }

    fun disconnect() {
        shouldReconnect = false
        isConnected = false
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        webSocket?.send("DISCONNECT\n\n\u0000")
        webSocket?.close(1000, "Client closed")
        webSocket = null
    }

    private fun escapeJson(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
