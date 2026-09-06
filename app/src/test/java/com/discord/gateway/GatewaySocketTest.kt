package com.discord.gateway

import com.discord.api.presence.ClientStatus
import com.discord.gateway.io.GuildSubscriptions
import com.discord.gateway.io.IdentifyClientState
import com.discord.models.domain.ModelGateway
import com.discord.stores.GuildSubscriptionPayload
import com.discord.utilities.websocket.GatewayWebSocket
import com.discord.utilities.websocket.GatewayWebSocketFactory
import com.discord.utilities.websocket.GatewayWebSocketListener
import com.discord.utilities.websocket.GatewayStreamDecoder
import com.google.gson.JsonParser
import java.util.PriorityQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewaySocketTest {
    @Test
    fun fastConnectIdentifiesOnOpenWithoutWaitingForHello() {
        val harness = Harness(identifyOnOpen = true)

        harness.connect()
        assertEquals(2, harness.socket.lastOp())
        val sentAfterOpen = harness.socket.sent.size

        harness.socket.serverMessage(hello())
        harness.scheduler.runCurrent()
        assertEquals(sentAfterOpen, harness.socket.sent.size)
    }

    @Test
    fun helloTimeoutStillRunsAfterFastConnectIdentifies() {
        for (fast in listOf(false, true)) {
            val harness = Harness(identifyOnOpen = fast)
            harness.connect()
            val stalled = harness.socket
            harness.scheduler.advanceBy(19_999)
            assertFalse(stalled.closed)
            harness.scheduler.advanceBy(1)
            assertTrue("Missing HELLO must time out even after early IDENTIFY", stalled.closed)
            harness.scheduler.advanceBy(3_000)
            assertEquals(2, harness.factory.sockets.size)
            assertFalse(harness.gateway.isSessionEstablished())
        }
    }

    @Test
    fun helloCancelsTheDeadlineAndClosingDoesNotResurrectFastConnect() {
        val healthy = Harness(identifyOnOpen = true)
        healthy.connectReady(1, "fixture-session", heartbeatInterval = 60_000)
        healthy.scheduler.advanceBy(23_000)
        assertFalse(healthy.socket.closed)
        assertEquals(1, healthy.factory.sockets.size)

        val closed = Harness(identifyOnOpen = true)
        closed.connect()
        closed.gateway.close()
        closed.scheduler.runCurrent()
        closed.scheduler.advanceBy(23_000)
        assertEquals(1, closed.factory.sockets.size)
        assertFalse(closed.gateway.isSessionEstablished())
    }

    @Test
    fun earlyReadyCannotDisableTheMissingHelloWatchdog() {
        val harness = Harness(identifyOnOpen = true)
        harness.connect()
        harness.socket.serverMessage("{\"op\":0,\"s\":1,\"t\":\"READY\",\"d\":{\"session_id\":\"fixture-session\"}}")
        harness.scheduler.runCurrent()
        assertTrue(harness.gateway.isSessionEstablished())
        harness.scheduler.advanceBy(20_000)
        assertTrue(harness.socket.closed)
        assertFalse(harness.gateway.isSessionEstablished())
    }

    @Test
    fun deferredHandlerReplaysEventsInOrderBeforeGoingLive() {
        val deferred = DeferredGatewayEventHandler()
        val events = mutableListOf<String>()
        val handler = object : GatewayEventHandler {
            override fun handleConnected(connected: Boolean) { events += "connected:$connected" }
            override fun handleConnectionReady(connectionReady: Boolean) { events += "ready:$connectionReady" }
            override fun handleFreshSession() { events += "fresh" }
            override fun handleDisconnect(authenticationFailed: Boolean) { events += "disconnect:$authenticationFailed" }
            override fun handleDispatch(type: String?, data: Any) { events += "dispatch:$type:$data" }
        }

        deferred.handleConnected(true)
        deferred.handleDispatch("READY", "payload")
        deferred.attach(handler)
        deferred.handleConnectionReady(true)

        assertEquals(listOf("connected:true", "dispatch:READY:payload", "ready:true"), events)
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
    fun foregroundHeartbeatReconnectsSuspendedSocketWithoutWaitingForNormalInterval() {
        val harness = Harness()
        harness.connectReady(sequence = 1, sessionId = "fixture-session", heartbeatInterval = 60_000)

        harness.gateway.handleActiveStateChange(com.discord.gateway.io.HeartbeatQos(true, 1, listOf("foregrounded")))
        harness.scheduler.runCurrent()
        harness.socket.serverMessage("{\"op\":11,\"d\":null}")
        harness.scheduler.runCurrent()
        harness.gateway.handleActiveStateChange(com.discord.gateway.io.HeartbeatQos(false, 1, emptyList()))
        harness.scheduler.runCurrent()
        harness.gateway.handleActiveStateChange(com.discord.gateway.io.HeartbeatQos(true, 1, listOf("foregrounded")))
        harness.scheduler.runCurrent()
        assertEquals(1, harness.socket.lastOp())
        harness.scheduler.advanceBy(4_999)
        assertEquals(1, harness.factory.sockets.size)
        harness.scheduler.advanceBy(1)
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
        assertEquals(1, harness.handler.freshSessions)
    }

    @Test
    fun resumableTransportCloseRetainsTheSession() {
        val harness = Harness()
        harness.connectReady(sequence = 42, sessionId = "fixture-session")

        harness.socket.serverClosed(1006, "transport lost")
        harness.scheduler.runCurrent()
        harness.scheduler.advanceBy(3_000)
        harness.socket.open()
        harness.socket.serverMessage(hello())
        harness.scheduler.runCurrent()

        assertEquals(6, harness.socket.lastOp())
        assertEquals("fixture-session", harness.socket.lastPayload()["d"].asJsonObject["session_id"].asString)
    }

    @Test
    fun nonResumableInvalidSessionIdentifiesAgain() {
        val harness = Harness()
        harness.connectReady(sequence = 9, sessionId = "fixture-session")
        harness.socket.serverMessage("{\"op\":9,\"d\":false}")
        harness.scheduler.runCurrent()

        val identify = harness.socket.lastPayload()
        assertEquals(2, identify["op"].asInt)
        // Dropped: current Discord does not send it.
        assertFalse(identify["d"].asJsonObject.has("large_threshold"))
        assertEquals(2, harness.handler.freshSessions)
        assertEquals(
            GatewayCapabilities.ENABLED.toInt(),
            identify["d"].asJsonObject["capabilities"].asInt,
        )
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
    fun resumableDeveloperDisconnectSendsResumeWithoutAReason() {
        val harness = Harness()
        harness.connectReady(sequence = 12, sessionId = "fixture-session")

        harness.gateway.disconnectForResumeTesting()
        harness.scheduler.runCurrent()
        harness.gateway.connect()
        harness.scheduler.runCurrent()
        harness.socket.open()
        harness.socket.serverMessage(hello())
        harness.scheduler.runCurrent()

        assertEquals(6, harness.socket.lastOp())
        assertEquals("fixture-session", harness.socket.lastPayload()["d"].asJsonObject["session_id"].asString)
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

    @Test
    fun verboseSocketLoggingDoesNotReparseLargePayloads() {
        val harness = Harness()
        harness.connectReady(sequence = 1, sessionId = "fixture-session")
        harness.socket.serverMessage(
            "{\"op\":0,\"s\":2,\"t\":\"FUTURE_EVENT\",\"d\":{\"padding\":\"${"x".repeat(300_000)}\"}}",
        )
        harness.scheduler.runCurrent()

        assertTrue(harness.logger.inbound.last().startsWith("<redacted large gateway payload:"))
    }

    @Test
    fun staleSocketOpenAfterRetryDoesNotIdentify() {
        val harness = Harness(identifyOnOpen = true)
        harness.gateway.connect()
        harness.scheduler.runCurrent()
        val stale = harness.factory.sockets.single()
        stale.fail()
        harness.scheduler.runCurrent()
        harness.scheduler.advanceBy(10_000)
        val replacement = harness.factory.sockets.last()

        stale.open()
        replacement.open()
        harness.scheduler.runCurrent()

        assertTrue(stale.sent.isEmpty())
        assertEquals(listOf(2), replacement.sent.map { JsonParser.parseString(it).asJsonObject["op"].asInt })
    }

    private class Harness(identifyOnOpen: Boolean = false) {
        val scheduler = FakeScheduler()
        val factory = FakeSocketFactory()
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
            identifyOnOpen = identifyOnOpen,
            decoderFactory = { IdentityDecoder() },
        )

        val socket: FakeSocket get() = factory.sockets.last()

        fun connect() {
            gateway.connect()
            scheduler.runCurrent()
            socket.open()
            scheduler.runCurrent()
        }

        fun connectReady(sequence: Int, sessionId: String, heartbeatInterval: Long = 1_000) {
            connect()
            socket.serverMessage(hello(heartbeatInterval))
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
        var freshSessions = 0
        val disconnects = mutableListOf<Boolean>()
        val dispatches = mutableListOf<Pair<String?, String>>()

        override fun handleConnected(connected: Boolean) = Unit
        override fun handleConnectionReady(connectionReady: Boolean) { readyStates += connectionReady }
        override fun handleFreshSession() { freshSessions++ }
        override fun handleDisconnect(authenticationFailed: Boolean) { disconnects += authenticationFailed }
        override fun handleDispatch(type: String?, data: Any) {
            dispatches += type to data.toString()
        }
    }

    private class FakeSocketFactory : GatewayWebSocketFactory {
        val sockets = mutableListOf<FakeSocket>()

        override fun connect(url: String, listener: GatewayWebSocketListener): GatewayWebSocket {
            assertTrue(url.startsWith("wss://"))
            return FakeSocket(listener).also(sockets::add)
        }
    }

    private class FakeSocket(
        private val listener: GatewayWebSocketListener,
    ) : GatewayWebSocket {
        val sent = mutableListOf<String>()
        var closed = false

        override fun send(text: String): Boolean = sent.add(text)
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        fun open() = listener.onOpen()
        fun fail() = listener.onFailure(IllegalStateException("fixture failure"))
        fun serverClosed(code: Int, reason: String) = listener.onClosed(code, reason)
        fun serverMessage(json: String) = listener.onBinaryMessage(json.toByteArray(Charsets.UTF_8))
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

    private class IdentityDecoder : GatewayStreamDecoder {
        override fun decode(bytes: ByteArray) = listOf(bytes)
        override fun reset() = Unit
    }

    private companion object {
        fun hello(interval: Long = 1_000) =
            "{\"op\":10,\"d\":{\"heartbeat_interval\":$interval,\"_trace\":[\"edge\"]}}"
    }
}
