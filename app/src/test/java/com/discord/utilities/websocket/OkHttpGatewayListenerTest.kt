package com.discord.utilities.websocket

import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException

class OkHttpGatewayListenerTest {

    private class Recorder : GatewayWebSocketListener {
        val events = mutableListOf<String>()
        override fun onClosed(code: Int, reason: String) { events += "closed $code" }
        override fun onFailure(error: Throwable) { events += "failure ${error.javaClass.simpleName}" }
    }

    private class FakeSocket : WebSocket {
        val closes = mutableListOf<Int>()
        override fun request() = Request.Builder().url("https://gateway.invalid").build()
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true.also { closes += code }
        override fun cancel() = Unit
    }

    @Test
    fun `a server close is reported with its code even when the connection then drops`() {
        val recorder = Recorder()
        val socket = FakeSocket()
        val adapter = OkHttpGatewayListener(recorder)

        // What a revoked token looks like: the close frame, then the connection going away.
        adapter.onClosing(socket, 4004, "Authentication failed.")
        adapter.onFailure(socket, EOFException(), null)
        adapter.onClosed(socket, 4004, "Authentication failed.")

        assertEquals(listOf("closed 4004"), recorder.events)
        assertEquals(listOf(1000), socket.closes)
    }
}
