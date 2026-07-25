package com.discord.gateway

import android.util.Log
import com.discord.api.activity.Activity
import com.discord.api.presence.ClientStatus
import com.discord.gateway.io.Hello
import com.discord.gateway.io.Identify
import com.discord.gateway.io.IdentifyClientState
import com.discord.gateway.io.Incoming
import com.discord.gateway.io.IncomingParser
import com.discord.gateway.io.Outgoing
import com.discord.gateway.io.PresenceUpdate
import com.discord.gateway.io.Resume
import com.discord.gateway.opcodes.Opcode
import com.discord.models.domain.ModelPayload
import com.discord.stores.GuildSubscriptionPayload
import com.discord.utilities.logging.BootLog
import com.discord.utilities.logging.BootLogEvent
import com.discord.utilities.websocket.GatewayWebSocket
import com.discord.utilities.websocket.GatewayWebSocketFactory
import com.discord.utilities.websocket.GatewayWebSocketListener
import com.discord.utilities.websocket.ZlibStreamDecoder
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.Locale

interface GatewayEventHandler {
    fun handleConnected(connected: Boolean)
    fun handleConnectionReady(connectionReady: Boolean)
    fun handleDisconnect(authenticationFailed: Boolean)
    fun handleDispatch(type: String?, data: Any)
}

data class IdentifyData(
    val token: String,
    val clientState: IdentifyClientState?,
) {
    override fun toString(): String = "IdentifyData(token=<redacted>, clientState=$clientState)"
}

fun interface GatewayCancellable {
    fun cancel()
}

interface GatewayScheduler {
    fun now(): Long
    fun schedule(delayMs: Long, action: () -> Unit): GatewayCancellable
}

class RealGatewayScheduler : GatewayScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "GatewaySocket").apply { isDaemon = true }
    }

    override fun now(): Long = System.currentTimeMillis()

    override fun schedule(delayMs: Long, action: () -> Unit): GatewayCancellable {
        val future: ScheduledFuture<*> = executor.schedule(action, delayMs, TimeUnit.MILLISECONDS)
        return GatewayCancellable { future.cancel(false) }
    }
}

class GatewaySocket(
    private val identifyDataProvider: () -> IdentifyData?,
    private val identifyProperties: Map<String, Any>,
    private val eventHandler: GatewayEventHandler,
    private val scheduler: GatewayScheduler,
    private val discovery: GatewayDiscovery,
    private val webSocketFactory: GatewayWebSocketFactory,
    private val jitter: () -> Double = Math::random,
) {
    private enum class State { DISCONNECTED, DISCOVERING, CONNECTING, IDENTIFYING, RESUMING, CONNECTED }

    private val gson: Gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .serializeNulls()
        .registerDiscordApiTypeAdapters()
        .create()
    private val incomingParser = IncomingParser(gson)
    private val decoder = ZlibStreamDecoder()
    private var state = State.DISCONNECTED
    private var socket: GatewayWebSocket? = null
    private var generation = 0
    private var sequence = 0
    private var sessionId: String? = null
    private var token: String? = null
    private var heartbeatInterval = Long.MAX_VALUE
    private var heartbeatAcknowledged = true
    private var lastHeartbeatAck = 0L
    private var heartbeatTask: GatewayCancellable? = null
    private var helloTimeoutTask: GatewayCancellable? = null
    private var reconnectTask: GatewayCancellable? = null
    private var failures = 0
    private var backoffMs = MIN_BACKOFF_MS

    init { (eventHandler as? GatewayEventRouter)?.bind(this) }

    fun connect() {
        logInfo("connect requested")
        scheduler.schedule(0) {
            if (state == State.DISCONNECTED) discoverAndConnect()
        }
    }

    fun close(resetSession: Boolean = true) {
        logInfo("close requested resetSession=$resetSession")
        scheduler.schedule(0) {
            reconnectTask?.cancel()
            reconnectTask = null
            cleanup(1000, "Client closed")
            state = State.DISCONNECTED
            if (resetSession) clearSession()
            eventHandler.handleConnected(false)
            eventHandler.handleConnectionReady(false)
        }
    }

    fun updateGuildSubscriptions(payload: GuildSubscriptionPayload) {
        scheduler.schedule(0) { if (state == State.CONNECTED) send(Opcode.GUILD_SUBSCRIPTIONS, payload) }
    }

    fun presenceUpdate(
        status: ClientStatus?,
        since: Long? = null,
        activities: List<Activity> = emptyList(),
        afk: Boolean? = false,
    ) {
        scheduler.schedule(0) {
            if (state == State.CONNECTED) {
                send(Opcode.PRESENCE_UPDATE, PresenceUpdate(status?.name?.lowercase(Locale.ROOT), since, activities, afk))
            }
        }
    }

    fun isSessionEstablished(): Boolean = state == State.CONNECTED

    private fun discoverAndConnect(ignoreSticky: Boolean = failures > FAILURE_THRESHOLD) {
        state = State.DISCOVERING
        BootLog.record(BootLogEvent.GATEWAY_DISCOVERY)
        val url = try {
            discovery.discover(ignoreSticky)
        } catch (_: Throwable) {
            logWarn("gateway discovery failed")
            scheduleReconnect("Gateway discovery failed")
            return
        }
        state = State.CONNECTING
        decoder.reset()
        val currentGeneration = ++generation
        val endpoint = url.trimEnd('/') + "/?encoding=json&v=9&compress=zlib-stream"
        logInfo("connecting host=${runCatching { java.net.URI(url).host }.getOrNull().orEmpty()}")
        helloTimeoutTask = scheduler.schedule(HELLO_TIMEOUT_MS) {
            if (currentGeneration == generation && state == State.CONNECTING) {
                reconnect("Gateway HELLO timeout", immediate = false)
            }
        }
        socket = try {
            webSocketFactory.connect(endpoint, object : GatewayWebSocketListener {
                override fun onOpen() {
                    BootLog.record(BootLogEvent.GATEWAY_SOCKET_OPEN)
                    logInfo("websocket opened; waiting for HELLO")
                }

                override fun onBinaryMessage(bytes: ByteArray) = serialized(currentGeneration) {
                    try {
                        decoder.decode(bytes).forEach(::handleMessage)
                    } catch (error: Throwable) {
                        logError("inflate/parse failure: ${error.javaClass.simpleName}", error)
                        reconnect("Gateway inflate/parse failure: ${error.javaClass.simpleName}", immediate = false)
                    }
                }

                override fun onClosed(code: Int, reason: String) = serialized(currentGeneration) {
                    handleSocketClosed(code, reason)
                }

                override fun onFailure(error: Throwable) = serialized(currentGeneration) {
                    logWarn("websocket failure: ${error.javaClass.simpleName}")
                    scheduleReconnect("Gateway socket failure: ${error.javaClass.simpleName}")
                }
            })
        } catch (_: Throwable) {
            scheduleReconnect("Gateway socket creation failed")
            null
        }
    }

    private fun serialized(expectedGeneration: Int, action: () -> Unit) {
        scheduler.schedule(0) {
            if (expectedGeneration == generation) action()
        }
    }

    private fun resumeOrIdentify() {
        if (sessionId != null && lastHeartbeatAck != 0L && scheduler.now() - lastHeartbeatAck <= RESUME_WINDOW_MS) {
            state = State.RESUMING
            logInfo("sending RESUME seq=$sequence")
            send(Opcode.RESUME, Resume(token, sessionId, sequence))
            return
        }
        identify()
    }

    private fun identify() {
        clearSession()
        val data = identifyDataProvider()
        if (data == null) {
            terminalDisconnect(authenticationFailed = true, code = 4004, reason = "No connection info provided")
            return
        }
        state = State.IDENTIFYING
        BootLog.record(BootLogEvent.GATEWAY_IDENTIFY)
        logInfo("sending IDENTIFY")
        token = data.token
        send(
            Opcode.IDENTIFY,
            Identify(data.token, 100, true, 351, identifyProperties, data.clientState),
        )
    }

    private fun handleMessage(json: String) {
        val incoming = incomingParser.parse(json)
        incoming.seq?.let { sequence = it }
        when (incoming.opcode) {
            Opcode.DISPATCH -> handleDispatch(incoming)
            Opcode.HEARTBEAT -> heartbeat()
            Opcode.RECONNECT -> reconnect("Gateway requested reconnect", immediate = true)
            Opcode.INVALID_SESSION -> {
                if (incoming.data == true) resumeOrIdentify() else {
                    eventHandler.handleConnected(false)
                    eventHandler.handleConnectionReady(false)
                    identify()
                }
            }
            Opcode.HELLO -> handleHello(incoming.data as Hello)
            Opcode.HEARTBEAT_ACK -> {
                heartbeatAcknowledged = true
                lastHeartbeatAck = scheduler.now()
            }
            else -> Unit
        }
    }

    private fun handleHello(hello: Hello) {
        BootLog.record(BootLogEvent.GATEWAY_HELLO)
        logInfo("HELLO heartbeatMs=${hello.heartbeatInterval}")
        helloTimeoutTask?.cancel()
        helloTimeoutTask = null
        heartbeatInterval = hello.heartbeatInterval.toLong()
        heartbeatAcknowledged = true
        scheduleHeartbeat()
        if (state == State.CONNECTING) resumeOrIdentify()
    }

    private fun scheduleHeartbeat() {
        heartbeatTask?.cancel()
        heartbeatTask = scheduler.schedule(heartbeatInterval) {
            if (!heartbeatAcknowledged) {
                scheduleReconnect("Gateway heartbeat ACK timeout")
            } else {
                heartbeat()
                scheduleHeartbeat()
            }
        }
    }

    private fun heartbeat() {
        heartbeatAcknowledged = false
        send(Opcode.HEARTBEAT, sequence)
    }

    private fun handleDispatch(incoming: Incoming) {
        logDebug("dispatch type=${incoming.type.orEmpty()} seq=${incoming.seq ?: sequence}")
        val data = incoming.data
        when (incoming.type) {
            "READY" -> {
                val ready = data as? ModelPayload
                if (ready?.sessionId == null) {
                    reconnect("Invalid READY payload", immediate = false)
                    return
                }
                sessionId = ready.sessionId
                BootLog.record(BootLogEvent.GATEWAY_DATA_READY)
                establishConnection()
            }
            "RESUMED" -> establishConnection()
        }
        if (data != null) {
            eventHandler.handleDispatch(incoming.type, data)
        } else if (incoming.type in EXPECTED_NULL_DATA_EVENTS) {
            eventHandler.handleDispatch(incoming.type, Unit)
        }
    }

    private fun establishConnection() {
        failures = 0
        backoffMs = MIN_BACKOFF_MS
        reconnectTask?.cancel()
        reconnectTask = null
        state = State.CONNECTED
        BootLog.record(BootLogEvent.GATEWAY_CONNECTED)
        logInfo("connection established")
        eventHandler.handleConnected(true)
        eventHandler.handleConnectionReady(true)
    }

    private fun handleSocketClosed(code: Int, reason: String) {
        if (code == 4004) {
            terminalDisconnect(true, code, reason)
            return
        }
        if (code != 4000) clearSession()
        scheduleReconnect("Gateway closed $code: $reason")
    }

    private fun reconnect(reason: String, immediate: Boolean) {
        cleanup(if (immediate) 1000 else 4000, reason)
        eventHandler.handleConnected(false)
        eventHandler.handleConnectionReady(false)
        eventHandler.handleDisconnect(false)
        state = State.DISCONNECTED
        if (immediate) discoverAndConnect() else scheduleReconnect(reason)
    }

    private fun scheduleReconnect(reason: String) {
        logWarn("reconnect scheduled: ${reason.take(120)}")
        cleanup(4000, reason)
        eventHandler.handleConnected(false)
        eventHandler.handleConnectionReady(false)
        state = State.DISCONNECTED
        if (reconnectTask != null) return
        failures++
        backoffMs = minOf(MAX_BACKOFF_MS, backoffMs + (2 * backoffMs * jitter()).toLong())
        if (failures > FAILURE_THRESHOLD) discovery.invalidate()
        reconnectTask = scheduler.schedule(backoffMs) {
            reconnectTask = null
            discoverAndConnect()
        }
    }

    private fun terminalDisconnect(authenticationFailed: Boolean, code: Int, reason: String) {
        cleanup(code, reason)
        clearSession()
        state = State.DISCONNECTED
        eventHandler.handleConnected(false)
        eventHandler.handleConnectionReady(false)
        eventHandler.handleDisconnect(authenticationFailed)
    }

    private fun cleanup(code: Int, reason: String) {
        generation++
        heartbeatTask?.cancel()
        heartbeatTask = null
        helloTimeoutTask?.cancel()
        helloTimeoutTask = null
        decoder.reset()
        socket?.close(code, reason.take(123))
        socket = null
    }

    private fun clearSession() {
        sequence = 0
        sessionId = null
    }

    private fun <T> send(opcode: Opcode, data: T?) {
        socket?.send(gson.toJson(Outgoing(opcode.apiInt, data)))
    }

    private fun logInfo(message: String) { runCatching { Log.i(TAG, message) } }
    private fun logWarn(message: String) { runCatching { Log.w(TAG, message) } }
    private fun logDebug(message: String) { runCatching { Log.d(TAG, message) } }
    private fun logError(message: String, error: Throwable) { runCatching { Log.e(TAG, message, error) } }

    private companion object {
        const val TAG = "ManticordGateway"
        const val HELLO_TIMEOUT_MS = 20_000L
        const val RESUME_WINDOW_MS = 180_000L
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 10_000L
        const val FAILURE_THRESHOLD = 4
        val EXPECTED_NULL_DATA_EVENTS = setOf("USER_SUBSCRIPTIONS_UPDATE", "USER_PAYMENT_SOURCES_UPDATE")
    }
}
