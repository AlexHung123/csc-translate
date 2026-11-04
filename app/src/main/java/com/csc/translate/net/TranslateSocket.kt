package com.csc.translate.net

import android.util.Log
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicBoolean

class TranslateSocket(
    private val client: OkHttpClient,
    private val json: Json,
    baseUrl: String,
    private val handshake: Handshake,
    private val onEvent: (ServerEvent) -> Unit,
    private val onDisconnected: (String?) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private val socket: WebSocket

    init {
        val request = Request.Builder().url(websocketUrl(baseUrl)).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val ok = webSocket.send(handshake.toJson())
                if (!ok) fail("Could not send handshake")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { json.decodeFromString<ServerEvent>(text) }
                    .getOrElse {
                        Log.w(TAG, "bad event: $text", it)
                        return
                    }
                if (event.event == "done" || event.event == "error") {
                    terminal.set(true)
                }
                onEvent(event)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // TTS audio is unused in v1.
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closed.compareAndSet(false, true) && !terminal.get()) {
                    onDisconnected("Translation connection closed")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "websocket failure", t)
                if (closed.compareAndSet(false, true) && !terminal.get()) {
                    onDisconnected(t.message ?: "Translation connection closed")
                }
            }
        })
    }

    fun sendPcm(frame: ByteArray): Boolean {
        if (closed.get()) return false
        return socket.send(frame.toByteString())
    }

    fun sendEof(): Boolean {
        if (closed.get()) return false
        terminal.set(true)
        return socket.send("""{"eof":true}""")
    }

    fun close() {
        terminal.set(true)
        if (closed.compareAndSet(false, true)) {
            socket.close(1000, "client")
        }
    }

    private fun fail(message: String) {
        if (closed.compareAndSet(false, true) && !terminal.get()) {
            onDisconnected(message)
        }
        socket.cancel()
    }

    private companion object {
        const val TAG = "CscTranslate"
    }
}
