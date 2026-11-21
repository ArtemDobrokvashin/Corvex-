package com.corvex.mobile.data.network.stomp

import com.corvex.mobile.data.security.SecureStorage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StompClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val secureStorage: SecureStorage
) {
    private var webSocket: WebSocket? = null

    fun connect(onMessage: (String) -> Unit, onError: (Throwable) -> Unit) {
        val baseUrl = secureStorage.serverUrl?.trimEnd('/') ?: return
        val wsUrl = baseUrl.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
        val token = secureStorage.accessToken ?: return

        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $token")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val connectFrame = "CONNECT\naccept-version:1.2\nheart-beat:10000,10000\n\n\u0000"
                webSocket.send(connectFrame)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError(t)
            }
        })
    }

    fun subscribe(destination: String, id: String) {
        val frame = "SUBSCRIBE\nid:$id\ndestination:$destination\n\n\u0000"
        webSocket?.send(frame)
    }

    fun send(destination: String, bodyJson: String) {
        val frame = "SEND\ndestination:$destination\ncontent-type:application/json\n\n$bodyJson\u0000"
        webSocket?.send(frame)
    }

    fun disconnect() {
        webSocket?.send("DISCONNECT\n\n\u0000")
        webSocket?.close(1000, "Client closed")
        webSocket = null
    }
}
