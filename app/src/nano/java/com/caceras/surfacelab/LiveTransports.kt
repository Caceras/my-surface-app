package com.caceras.surfacelab

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * Gemini Live WebSocket for the Nano build. Callbacks arrive on OkHttp's
 * thread; LiveSession moves them to the main thread and fences stale sockets.
 * The key travels only in the TLS-protected handshake URL, as Google documents.
 */
object LiveTransports {
    const val available = true
    private val client by lazy {
        OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).connectTimeout(15, TimeUnit.SECONDS).build()
    }

    fun connect(url: String, listener: LiveTransportListener): LiveTransport {
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.opened()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.message(text)
            // Gemini Live sends JSON in binary frames.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.message(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.closed("$code $reason".trim())
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.failed(response?.let { "HTTP ${it.code} ${it.message}".trim() } ?: (t.message ?: "Connection failed"))
        })
        return object : LiveTransport {
            override fun send(text: String) = socket.send(text)
            override fun close() { socket.close(1000, "done") }
        }
    }
}
