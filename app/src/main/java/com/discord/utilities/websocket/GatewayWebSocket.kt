package com.discord.utilities.websocket

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean

interface GatewayWebSocket {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String?): Boolean
}

interface GatewayWebSocketListener {
    fun onOpen() = Unit
    fun onBinaryMessage(bytes: ByteArray) = Unit
    fun onClosed(code: Int, reason: String) = Unit
    fun onFailure(error: Throwable) = Unit
}

fun interface GatewayWebSocketFactory {
    fun connect(url: String, listener: GatewayWebSocketListener): GatewayWebSocket
}

class OkHttpGatewayWebSocketFactory(
    private val client: OkHttpClient = OkHttpClient(),
) : GatewayWebSocketFactory {
    override fun connect(url: String, listener: GatewayWebSocketListener): GatewayWebSocket {
        require(url.startsWith("wss://")) { "Gateway WebSocket must use TLS" }
        lateinit var socket: WebSocket
        socket = client.newWebSocket(Request.Builder().url(url).build(), OkHttpGatewayListener(listener))
        return object : GatewayWebSocket {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close(code: Int, reason: String?): Boolean = socket.close(code, reason)
        }
    }
}

/**
 * Adapts OkHttp's callbacks, reporting the socket's end exactly once.
 *
 * The server's close frame (4004 for a token revoked elsewhere, 4007/4009 for a dead session)
 * arrives in [onClosing]; OkHttp only calls [onClosed] after this side closes too. Discord drops
 * the connection first, so unanswered, every close surfaced as a bare failure: a logged-out
 * client reconnected and re-identified with its dead token forever.
 */
internal class OkHttpGatewayListener(private val listener: GatewayWebSocketListener) : WebSocketListener() {
    private val ended = AtomicBoolean(false)

    override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onBinaryMessage(bytes.toByteArray())

    override fun onMessage(webSocket: WebSocket, text: String) =
        listener.onFailure(IllegalStateException("Gateway sent uncompressed text"))

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        // 1000 rather than echoing [code]: a frame without a status reads as 1005, which may not be sent.
        webSocket.close(1000, null)
        end { listener.onClosed(code, reason) }
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end { listener.onClosed(code, reason) }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = end { listener.onFailure(t) }

    private inline fun end(report: () -> Unit) {
        if (ended.compareAndSet(false, true)) report()
    }
}
