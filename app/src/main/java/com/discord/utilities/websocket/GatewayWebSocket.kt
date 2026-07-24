package com.discord.utilities.websocket

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

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
        socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onBinaryMessage(bytes.toByteArray())
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onFailure(IllegalStateException("Gateway sent uncompressed text"))
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(code, reason)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure(t)
            },
        )
        return object : GatewayWebSocket {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close(code: Int, reason: String?): Boolean = socket.close(code, reason)
        }
    }
}
