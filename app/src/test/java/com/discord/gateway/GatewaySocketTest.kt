package com.discord.gateway

import com.discord.api.presence.ClientStatus
import com.discord.gateway.io.GuildSubscriptions
import com.discord.gateway.io.IdentifyClientState
import com.discord.models.domain.ModelGateway
import com.discord.stores.GuildSubscriptionPayload
import com.discord.utilities.websocket.GatewayWebSocket
import com.discord.utilities.websocket.GatewayWebSocketFactory
import com.discord.utilities.websocket.GatewayWebSocketListener
import com.discord.utilities.websocket.ZlibStreamDecoder
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.util.PriorityQueue
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewaySocketTest {
    @Test
    fun fragmentedZlibStreamEmitsOnlyCompleteMessages() {
        val fixture = ZlibFixture()
        val compressed = fixture.encode("{\"op\":10,\"d\":{\"heartbeat_interval\":1000,\"_trace\":[\"edge\"]}}")
        val decoder = ZlibStreamDecoder()

        compressed.dropLast(1).forEach { assertTrue(decoder.decode(byteArrayOf(it)).isEmpty()) }
        assertEquals(1, decoder.decode(byteArrayOf(compressed.last())).size)
    }

    @Test
    fun helloStartsHeartbeatAndMissingAckReconnectsWithBackoff() {
        val harness = Harness()
        harness.connect()
        harness.socket.serverMessage(hello())
        harness.scheduler.runCurrent()

        harness.scheduler.advanceBy(1000)
        assertEquals(1, harness.socket.lastOp())
        harness.socket.serverMessage("{\"op\":11,\"d\":null}")
        harness.scheduler.runCurrent()
        harness.scheduler.advanceBy(1000)
        assertEquals(1, harness.socket.lastOp())

        harness.scheduler.advanceBy(1000)
        assertEquals(1, harness.factory.sockets.size)
        harness.scheduler.advanceBy(3000)
        assertEquals(2, harness.factory.sockets.size)
    }

    @Test
    fun reconnectResumesLatestSequenceAndDispatchesIt() {
        val harness = Harness()
        harness.connectReady(sequence = 7, sessionId = "fixture-session")
        harness.scheduler.advanceBy(1)
        harness.socket.serverMessage("{\"op\":11,\"d\":null}")
        harness.scheduler.runCurrent()
        harness.socket.serverMessage("{\"op\":0,\"s\":42,\"t\":\"MESSAGE_CREATE\",\"d\":{\"id\":\"1\"}}")
        harness.scheduler.runCurrent()
        assertEquals("MESSAGE_CREATE", harness.handler.dispatches.last().first)

        harness.socket.fail()
        harness.scheduler.runCurrent()
        harness.scheduler.advanceBy(3000)
        harness.socket.open()
        harness.socket.serverMessage(hello())
        harness.scheduler.runCurrent()

        val resume = harness.socket.lastPayload()
        assertEquals(6, resume["op"].asInt)
        assertEquals("fixture-session", resume["d"].asJsonObject["session_id"].asString)
        assertEquals(42, resume["d"].asJsonObject["seq"].asInt)
    }

    @Test
    fun nonResumableInvalidSessionIdentifiesAgain() {
        val harness = Harness()
        harness.connectReady(sequence = 9, sessionId = "fixture-session")
        harness.socket.serverMessage("{\"op\":9,\"d\":false}")
        harness.scheduler.runCurrent()

        val identify = harness.socket.lastPayload()
        assertEquals(2, identify["op"].asInt)
        assertEquals(100, identify["d"].asJsonObject["large_threshold"].asInt)
        // 479 = 351 plus NON_CHANNEL_READ_STATES (1 shl 7), which READY needs to send
        // guild-event and onboarding read states.
        assertEquals(479, identify["d"].asJsonObject["capabilities"].asInt)
        assertFalse(harness.handler.readyStates.last())
    }

    @Test
    fun reconnectOpcodeIsImmediateAndKeepsSession() {
        val harness = Harness()
        harness.connectReady(sequence = 12, sessionId = "fixture-session")
        harness.scheduler.advanceBy(1)
        harness.socket.serverMessage("{\"op\":11,\"d\":null}")
        harness.scheduler.runCurrent()
        harness.socket.serverMessage("{\"op\":7,\"d\":null}")
        harness.scheduler.runCurrent()

        assertEquals(2, harness.factory.sockets.size)
        harness.socket.open()
        harness.socket.serverMessage(hello())
        harness.scheduler.runCurrent()
        assertEquals(6, harness.socket.lastOp())
        assertEquals(listOf(false), harness.handler.disconnects)
    }

    @Test
    fun connectedSocketSendsGuildMemberSubscriptions() {
        val harness = Harness()
        harness.connectReady(sequence = 1, sessionId = "fixture-session")

        harness.gateway.updateGuildSubscriptions(2L, GuildSubscriptions(mapOf(3L to listOf(listOf(0, 99))), null, null, null, null, null))
        harness.scheduler.runCurrent()

        val payload = harness.socket.lastPayload()
        assertEquals(14, payload["op"].asInt)
        assertEquals(99, payload["d"].asJsonObject["channels"].asJsonObject["3"].asJsonArray[0].asJsonArray[1].asInt)
    }

    @Test
    fun presenceUpdateSendsOpcodeThreeOnlyWhenConnected() {
        val harness = Harness()
        harness.gateway.presenceUpdate(ClientStatus.DND, null, emptyList(), false)
        harness.scheduler.runCurrent()
        assertTrue(harness.factory.sockets.isEmpty())

        harness.connectReady(sequence = 1, sessionId = "fixture-session")
        val sentBefore = harness.socket.sent.size
        harness.gateway.presenceUpdate(ClientStatus.DND, null, emptyList(), false)
        harness.scheduler.runCurrent()

        assertEquals(sentBefore + 1, harness.socket.sent.size)
        assertEquals(
            "{\"op\":3,\"d\":{\"status\":\"dnd\",\"since\":null,\"activities\":[],\"afk\":false}}",
            harness.socket.sent.last(),
        )
    }

    @Test
    fun verboseSocketLoggingRedactsSessionAndToken() {
        val harness = Harness()
        harness.connectReady(sequence = 1, sessionId = "fixture-session")

        assertTrue(harness.socket.sent.any { it.contains("fixture-token") })
        assertFalse(harness.logger.outbound.joinToString().contains("fixture-token"))
        assertFalse(harness.logger.inbound.joinToString().contains("fixture-session"))
        assertTrue(harness.logger.outbound.any { it.contains("<redacted>") })
        assertTrue(harness.logger.inbound.any { it.contains("<redacted>") })
    }

    private class Harness {
        val scheduler = FakeScheduler()
        val fixture = ZlibFixture()
        val factory = FakeSocketFactory(fixture)
        val handler = RecordingHandler()
        val logger = RecordingLogger()
        val gateway = GatewaySocket(
            identifyDataProvider = {
                IdentifyData(
                    "fixture-token",
                    IdentifyClientState(emptyMap(), 0, 0, 0),
                )
            },
            identifyProperties = { mapOf("os" to "Android", "browser" to "Discord Android", "device" to "fixture") },
            eventHandler = handler,
            scheduler = scheduler,
            discovery = GatewayDiscovery { ModelGateway("wss://gateway.example") },
            webSocketFactory = factory,
            gatewaySocketLogger = logger,
            jitter = { 1.0 },
        )

        val socket: FakeSocket get() = factory.sockets.last()

        fun connect() {
            gateway.connect()
            scheduler.runCurrent()
            socket.open()
            scheduler.runCurrent()
        }

        fun connectReady(sequence: Int, sessionId: String) {
            connect()
            socket.serverMessage(hello())
            socket.serverMessage("{\"op\":0,\"s\":$sequence,\"t\":\"READY\",\"d\":{\"session_id\":\"$sessionId\"}}")
            scheduler.runCurrent()
            assertTrue(gateway.isSessionEstablished())
        }
    }

    private class RecordingLogger : GatewaySocketLogger {
        override val logLevel = GatewaySocketLogger.LogLevel.VERBOSE
        val inbound = mutableListOf<String>()
        val outbound = mutableListOf<String>()

        override fun logInboundMessage(rawMessage: String) { inbound += rawMessage }
        override fun logMessageInflateFailed(throwable: Throwable) = Unit
        override fun logOutboundMessage(rawMessage: String) { outbound += rawMessage }
    }

    private class RecordingHandler : GatewayEventHandler {
        val readyStates = mutableListOf<Boolean>()
        val disconnects = mutableListOf<Boolean>()
        val dispatches = mutableListOf<Pair<String?, String>>()

        override fun handleConnected(connected: Boolean) = Unit
        override fun handleConnectionReady(connectionReady: Boolean) { readyStates += connectionReady }
        override fun handleDisconnect(authenticationFailed: Boolean) { disconnects += authenticationFailed }
        override fun handleDispatch(type: String?, data: Any) {
            dispatches += type to data.toString()
        }
    }

    private class FakeSocketFactory(private val fixture: ZlibFixture) : GatewayWebSocketFactory {
        val sockets = mutableListOf<FakeSocket>()

        override fun connect(url: String, listener: GatewayWebSocketListener): GatewayWebSocket {
            assertTrue(url.startsWith("wss://"))
            return FakeSocket(listener, ZlibFixture()).also(sockets::add)
        }
    }

    private class FakeSocket(
        private val listener: GatewayWebSocketListener,
        private val fixture: ZlibFixture,
    ) : GatewayWebSocket {
        val sent = mutableListOf<String>()
        var closed = false

        override fun send(text: String): Boolean = sent.add(text)
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        fun open() = listener.onOpen()
        fun fail() = listener.onFailure(IllegalStateException("fixture failure"))
        fun serverMessage(json: String) = listener.onBinaryMessage(fixture.encode(json))
        fun lastPayload() = JsonParser.parseString(sent.last()).asJsonObject
        fun lastOp(): Int = lastPayload()["op"].asInt
    }

    private class FakeScheduler : GatewayScheduler {
        private data class Task(val at: Long, val order: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val tasks = PriorityQueue(compareBy<Task>({ it.at }, { it.order }))
        private var order = 0L
        private var time = 0L

        override fun now(): Long = time

        override fun schedule(delayMs: Long, action: () -> Unit): GatewayCancellable {
            val task = Task(time + delayMs, order++, action)
            tasks += task
            return GatewayCancellable { task.cancelled = true }
        }

        fun runCurrent() = advanceBy(0)

        fun advanceBy(milliseconds: Long) {
            val target = time + milliseconds
            while (tasks.isNotEmpty() && tasks.peek()!!.at <= target) {
                val task = tasks.remove()
                time = task.at
                if (!task.cancelled) task.action()
            }
            time = target
        }
    }

    private class ZlibFixture {
        private val deflater = Deflater()

        fun encode(json: String): ByteArray {
            deflater.setInput(json.toByteArray(Charsets.UTF_8))
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(256)
            do {
                val count = deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH)
                output.write(buffer, 0, count)
            } while (!deflater.needsInput())
            return output.toByteArray()
        }
    }

    private companion object {
        fun hello() = "{\"op\":10,\"d\":{\"heartbeat_interval\":1000,\"_trace\":[\"edge\"]}}"
    }
}
