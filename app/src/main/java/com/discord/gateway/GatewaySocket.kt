package com.discord.gateway

import android.os.SystemClock
import android.os.Trace
import android.util.Log
import com.discord.api.activity.Activity
import com.discord.api.presence.ClientStatus
import com.discord.gateway.io.ApplicationCommandRequest
import com.discord.gateway.io.CallConnect
import com.discord.gateway.io.CreateStream
import com.discord.gateway.io.DeleteStream
import com.discord.gateway.io.ForumUnreadsRequest
import com.discord.gateway.io.GuildMembersRequest
import com.discord.gateway.io.GuildSubscriptions
import com.discord.gateway.io.GuildSubscriptionsUpdate
import com.discord.gateway.io.Hello
import com.discord.gateway.io.Identify
import com.discord.gateway.io.IdentifyClientState
import com.discord.gateway.io.Incoming
import com.discord.gateway.io.IncomingParser
import com.discord.gateway.io.Outgoing
import com.discord.gateway.io.PresenceUpdate
import com.discord.gateway.io.Resume
import com.discord.gateway.io.SoundboardSoundsRequest
import com.discord.gateway.io.StreamPing
import com.discord.gateway.io.VoiceStateUpdate
import com.discord.gateway.io.VoiceStateUpdateNoPreferredRegion
import com.discord.gateway.io.WatchStream
import com.discord.gateway.opcodes.Opcode
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.deserialization.gson.InboundGatewayGsonParser
import com.discord.models.domain.ModelPayload
import com.discord.utilities.logging.BootLog
import com.discord.utilities.logging.BootLogEvent
import com.discord.utilities.logging.StartupMetrics
import com.discord.utilities.websocket.GatewayCompression
import com.discord.utilities.websocket.GatewayStreamDecoder
import com.discord.utilities.websocket.GatewayWebSocket
import com.discord.utilities.websocket.GatewayWebSocketFactory
import com.discord.utilities.websocket.GatewayWebSocketListener
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
    private val identifyProperties: () -> Map<String, Any?>,
    private val eventHandler: GatewayEventHandler,
    private val scheduler: GatewayScheduler,
    private val discovery: GatewayDiscovery,
    private val webSocketFactory: GatewayWebSocketFactory,
    private val gatewaySocketLogger: GatewaySocketLogger = NoopGatewaySocketLogger,
    private val jitter: () -> Double = Math::random,
    initialCompression: GatewayCompression = GatewayCompression.ZSTD,
    @Volatile private var identifyOnOpen: Boolean = false,
    decoderFactory: (GatewayCompression) -> GatewayStreamDecoder = GatewayCompression::createDecoder,
) {
    private enum class State { DISCONNECTED, DISCOVERING, CONNECTING, IDENTIFYING, RESUMING, CONNECTED }

    private val gson: Gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .serializeNulls()
        .registerDiscordApiTypeAdapters()
        .create()
    private val gsonOmitNulls: Gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerDiscordApiTypeAdapters()
        .create()
    private val incomingParser = IncomingParser(InboundGatewayGsonParser.gatewayGsonInstance)
    private val compression = initialCompression
    private var decoder: GatewayStreamDecoder = decoderFactory(compression)
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
    private var resetsSinceReady = 0
    private var resetHealthyTask: GatewayCancellable? = null
    private var backoffMs = MIN_BACKOFF_MS
    private var lastFrameReceivedNanos = 0L

    fun connect() {
        logInfo("connect requested")
        scheduler.schedule(0) {
            if (state == State.DISCONNECTED) discoverAndConnect()
        }
    }

    /**
     * Brings a pending reconnect forward, which is what the reference does when the app comes to the
     * foreground: a socket that dropped while the user was away may be sitting on a full backoff,
     * and they should not have to wait it out.
     */
    fun resetBackoff() {
        scheduler.schedule(0) {
            backoffMs = MIN_BACKOFF_MS
            if (state == State.DISCONNECTED && reconnectTask != null) {
                reconnectTask?.cancel()
                reconnectTask = null
                discoverAndConnect()
            }
        }
    }

    /**
     * Ends the session for good — the only caller is logout.
     *
     * 1000 is what tells the gateway that, and it is why nothing else may use this: a client that
     * intends to come back has to close with 4000 like [reconnect] does, or its RESUME is rejected.
     */
    fun close() {
        logInfo("close requested")
        scheduler.schedule(0) {
            reconnectTask?.cancel()
            reconnectTask = null
            cleanup(1000, "Client closed")
            state = State.DISCONNECTED
            clearSession()
            eventHandler.handleConnected(false)
            eventHandler.handleConnectionReady(false)
        }
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

    /**
     * A null [channelId] leaves voice. [shouldIncludePreferredRegion] must be false when the caller
     * has no opinion: sending an explicit null region asks the backend to *clear* the pinned region.
     */
    fun voiceStateUpdate(
        guildId: Long?,
        channelId: Long?,
        selfMute: Boolean,
        selfDeaf: Boolean,
        selfVideo: Boolean,
        preferredRegion: String? = null,
        shouldIncludePreferredRegion: Boolean = true,
    ) = scheduleSend {
        if (shouldIncludePreferredRegion) {
            send(Opcode.VOICE_STATE_UPDATE, VoiceStateUpdate(guildId, channelId, selfMute, selfDeaf, selfVideo, preferredRegion))
        } else {
            send(Opcode.VOICE_STATE_UPDATE, VoiceStateUpdateNoPreferredRegion(guildId, channelId, selfMute, selfDeaf, selfVideo))
        }
    }

    fun voiceServerPing() = scheduleSend { send<Unit>(Opcode.VOICE_SERVER_PING, null) }

    fun callConnect(channelId: Long) = scheduleSend {
        send(Opcode.CALL_CONNECT, CallConnect(channelId))
    }

    fun requestGuildMembers(
        guildIds: List<Long>,
        query: String? = null,
        userIds: List<Long>? = null,
        limit: Int? = DEFAULT_MEMBER_REQUEST_LIMIT,
    ) = scheduleSend {
        send(Opcode.REQUEST_GUILD_MEMBERS, GuildMembersRequest(guildIds, query, userIds, limit), omitNulls = true)
    }

    fun updateGuildSubscriptions(guildId: Long, subscriptions: GuildSubscriptions) = scheduleSend {
        send(
            Opcode.GUILD_SUBSCRIPTIONS,
            GuildSubscriptionsUpdate(
                guildId = guildId,
                typing = subscriptions.typing,
                activities = subscriptions.activities,
                members = subscriptions.members,
                channels = subscriptions.channels,
                threads = subscriptions.threads,
                threadMemberLists = subscriptions.threadMemberLists,
            ),
            omitNulls = true,
        )
    }

    fun streamCreate(streamType: String, channelId: Long, guildId: Long?, preferredRegion: String?) = scheduleSend {
        send(Opcode.STREAM_CREATE, CreateStream(streamType, channelId, guildId, preferredRegion))
    }

    fun streamWatch(streamKey: String) = scheduleSend { send(Opcode.STREAM_WATCH, WatchStream(streamKey)) }

    fun streamDelete(streamKey: String) = scheduleSend { send(Opcode.STREAM_DELETE, DeleteStream(streamKey)) }

    fun streamPing(streamKey: String) = scheduleSend { send(Opcode.OPCODE_STREAM_PING, StreamPing(streamKey)) }

    fun requestApplicationCommands(request: ApplicationCommandRequest) = scheduleSend {
        send(Opcode.REQUEST_GUILD_APPLICATION_COMMANDS, request, omitNulls = true)
    }

    fun requestForumUnreads(request: ForumUnreadsRequest) = scheduleSend {
        send(Opcode.REQUEST_FORUM_UNREADS, request, omitNulls = true)
    }

    fun requestSoundboardSounds(guildIds: List<Long>) = scheduleSend {
        send(Opcode.REQUEST_SOUNDBOARD_SOUNDS, SoundboardSoundsRequest(guildIds))
    }

    /**
     * Drops the session and reconnects after the cached client state turned out to be unusable.
     */
    fun resetOnError() {
        scheduler.schedule(0) {
            resetHealthyTask?.cancel()
            resetHealthyTask = null
            clearSession()
            reconnect("Reset on error", immediate = resetsSinceReady++ == 0)
        }
    }

    fun simulateReconnectForTesting() {
        scheduler.schedule(0) { reconnect("Simulated reconnect", immediate = true) }
    }

    fun isSessionEstablished(): Boolean = state == State.CONNECTED

    fun completeFastConnect() {
        identifyOnOpen = false
    }

    private fun scheduleSend(action: () -> Unit) {
        scheduler.schedule(0) { if (state == State.CONNECTED) action() }
    }

    private fun discoverAndConnect(ignoreSticky: Boolean = failures > FAILURE_THRESHOLD) {
        state = State.DISCOVERING
        lastFrameReceivedNanos = 0L
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
        val endpoint = url.trimEnd('/') + "/?encoding=json&v=9&compress=${compression.queryValue}"
        logInfo("connecting host=${runCatching { java.net.URI(url).host }.getOrNull().orEmpty()}")
        helloTimeoutTask = scheduler.schedule(HELLO_TIMEOUT_MS) {
            if (currentGeneration == generation && state == State.CONNECTING) {
                reconnect("Gateway HELLO timeout", immediate = false)
            }
        }
        socket = try {
            webSocketFactory.connect(endpoint, object : GatewayWebSocketListener {
                override fun onOpen() = serialized(currentGeneration) {
                    BootLog.record(BootLogEvent.GATEWAY_SOCKET_OPEN)
                    logInfo("websocket opened; waiting for HELLO")
                    if (identifyOnOpen && state == State.CONNECTING) identify()
                }

                override fun onBinaryMessage(bytes: ByteArray) {
                    val receivedNanos = SystemClock.elapsedRealtimeNanos()
                    serialized(currentGeneration) { handleBinaryMessage(bytes, receivedNanos) }
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

    private fun handleBinaryMessage(bytes: ByteArray, receivedNanos: Long) {
        try {
            val previousReceivedNanos = lastFrameReceivedNanos
            lastFrameReceivedNanos = receivedNanos
            val decodeStartedNanos = SystemClock.elapsedRealtimeNanos()
            Trace.beginSection("Gateway ${compression.queryValue} decode")
            val messages = try {
                decoder.decode(bytes)
            } finally {
                Trace.endSection()
            }
            val decodeNanos = SystemClock.elapsedRealtimeNanos() - decodeStartedNanos
            messages.forEachIndexed { index, message ->
                handleMessage(
                    message,
                    receivedNanos,
                    (receivedNanos - previousReceivedNanos).takeIf { previousReceivedNanos != 0L },
                    decodeNanos.takeIf { index == 0 } ?: 0L,
                )
            }
        } catch (error: Throwable) {
            if (gatewaySocketLogger.logLevel == GatewaySocketLogger.LogLevel.VERBOSE) {
                runCatching { gatewaySocketLogger.logMessageInflateFailed(error) }
            }
            logError("inflate/parse failure: ${error.javaClass.simpleName}: ${error.message}", error)
            reconnect("Gateway inflate/parse failure: ${error.javaClass.simpleName}", immediate = false)
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
        logInfo("sending IDENTIFY clientState=${data.clientState}")
        token = data.token
        send(
            Opcode.IDENTIFY,
            Identify(data.token, true, CAPABILITIES, identifyProperties(), data.clientState),
        )
    }

    private fun handleMessage(json: ByteArray, receivedNanos: Long, waitNanos: Long?, decodeNanos: Long) {
        if (gatewaySocketLogger.logLevel == GatewaySocketLogger.LogLevel.VERBOSE) {
            runCatching {
                gatewaySocketLogger.logInboundMessage(
                    if (json.size <= MAX_VERBOSE_LOG_BYTES) {
                        GatewayLogRedactor.redact(json.toString(Charsets.UTF_8))
                    } else {
                        "<redacted large gateway payload: ${json.size} bytes>"
                    },
                )
            }
        }
        Trace.beginSection("Gateway JSON parse")
        val parseStartedNanos = SystemClock.elapsedRealtimeNanos()
        val incoming = try {
            incomingParser.parse(json)
        } finally {
            Trace.endSection()
        }
        StartupMetrics.recordGatewayFrame(
            incoming.type ?: incoming.opcode.name,
            receivedNanos,
            waitNanos,
            decodeNanos,
            SystemClock.elapsedRealtimeNanos() - parseStartedNanos,
        )
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
        if (data != null && data !== IncomingParser.DISPATCH_UNPARSED) {
            eventHandler.handleDispatch(incoming.type, data)
        } else if (incoming.type in EXPECTED_NULL_DATA_EVENTS) {
            // These two arrive with no `d` at all; their stores only need the nudge.
            eventHandler.handleDispatch(incoming.type, Unit)
        }
    }

    private fun establishConnection() {
        failures = 0
        backoffMs = MIN_BACKOFF_MS
        reconnectTask?.cancel()
        reconnectTask = null
        // Not cleared here: the payload this READY carries has not been hydrated yet, and clearing
        // now would make every reset look like the first one and reconnect immediately forever.
        resetHealthyTask?.cancel()
        resetHealthyTask = scheduler.schedule(RESET_HEALTHY_MS) {
            resetHealthyTask = null
            resetsSinceReady = 0
        }
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

    /**
     * @param omitNulls drops null members instead of serialising them. Several request opcodes treat
     *   an explicit null as a meaningful "clear this" instruction, so absent and null differ.
     */
    private fun <T> send(opcode: Opcode, data: T?, omitNulls: Boolean = false) {
        val serializer = if (omitNulls) gsonOmitNulls else gson
        val rawMessage = try {
            serializer.toJson(Outgoing(opcode, data))
        } catch (error: Throwable) {
            logError("gateway serialization failed for opcode ${opcode.apiInt}", error)
            scheduleReconnect("Gateway serialization failed")
            return
        }
        runCatching { gatewaySocketLogger.logOutboundMessage(GatewayLogRedactor.redact(rawMessage)) }
        if (socket?.send(rawMessage) != true) scheduleReconnect("Gateway send failed")
    }

    private fun logInfo(message: String) { runCatching { Log.i(TAG, message) } }
    private fun logWarn(message: String) { runCatching { Log.w(TAG, message) } }
    private fun logDebug(message: String) { runCatching { Log.d(TAG, message) } }
    private fun logError(message: String, error: Throwable) { runCatching { Log.e(TAG, message, error) } }

    private companion object {

        /**
         * Identify capabilities. `NON_CHANNEL_READ_STATES` (1 shl 7) is what makes READY
         * carry guild-event and onboarding read states; without it they never arrive.
         */
        private const val CAPABILITIES = 479L or (1L shl 10) // CLIENT_STATE_V2
        private const val MAX_VERBOSE_LOG_BYTES = 256 * 1024
        const val TAG = "ManticordGateway"
        const val HELLO_TIMEOUT_MS = 20_000L
        const val RESUME_WINDOW_MS = 180_000L
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 10_000L
        const val FAILURE_THRESHOLD = 4

        /** How long a connection must survive before a further reset counts as the first again. */
        const val RESET_HEALTHY_MS = 120_000L
        const val DEFAULT_MEMBER_REQUEST_LIMIT = 100
        val EXPECTED_NULL_DATA_EVENTS = setOf("USER_SUBSCRIPTIONS_UPDATE", "USER_PAYMENT_SOURCES_UPDATE")
    }
}
