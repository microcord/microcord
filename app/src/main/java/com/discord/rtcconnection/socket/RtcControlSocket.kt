package com.discord.rtcconnection.socket

import androidx.annotation.AnyThread
import com.discord.app.AppLog
import com.discord.rtcconnection.dave.DaveSession
import com.discord.rtcconnection.dave.VoiceGatewayFrameCodec
import com.discord.rtcconnection.mediaengine.MediaEngineThreadExecutor
import com.discord.rtcconnection.socket.io.Opcodes
import com.discord.rtcconnection.socket.io.Payloads
import com.discord.utilities.networking.Backoff
import com.discord.utilities.time.Clock
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.KeyStore
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * The voice control WebSocket: session authentication, transport negotiation, heartbeats, resume
 * and reconnect for one voice server.
 *
 * Everything a listener sees happens on the media-engine thread ([executor]), because the listener
 * is `RtcConnection`, which drives the native engine. OkHttp delivers on its own reader thread, so
 * every callback is re-posted; the `webSocket === socket` identity check in each one is what stops
 * a superseded socket's late events from steering the live connection.
 *
 * Reference: `b.a.q.n0.a` (`RtcControlSocket`).
 */
internal class RtcControlSocket(
    val endpoint: String,
    private val token: String,
    private val sslSocketFactory: SSLSocketFactory?,
    private val executor: MediaEngineThreadExecutor,
    private val clock: Clock,
    loggingTagPrefix: String,
) : WebSocketListener() {

    /**
     * Where the handshake is.
     *
     * `CONNECTING` and `RECONNECTING` differ only in what happens when the socket opens: a fresh
     * connect identifies, a reconnect tries to resume first.
     */
    private enum class State { DISCONNECTED, CONNECTING, IDENTIFYING, RESUMING, CONNECTED, RECONNECTING }

    interface Listener : DaveSession.Listener {
        /** The socket is being opened. */
        fun onConnecting()

        /** The socket opened on a fresh connect; the listener should send `IDENTIFY`. */
        fun onSocketConnect()

        /** The socket opened on a reconnect and a `RESUME` was sent. */
        fun onSocketResume()

        /** Round-trip time in ms, from the handshake and from every heartbeat ack. */
        fun onHeartbeatAck(pingMs: Long)

        /** `READY`: the dedicated media server to send RTP to. */
        fun onReady(port: Int, ssrc: Int, ip: String, streams: List<Payloads.Stream>, modes: List<String>)

        /** `SELECT_PROTOCOL_ACK`: the RTP encryption mode and key. */
        fun onSelectProtocolAck(mode: String, secretKey: List<Int>)

        /** `SELECT_PROTOCOL_ACK`: the codecs the server picked. */
        fun onCodecs(audioCodec: String, videoCodec: String)

        /** `SELECT_PROTOCOL_ACK` or `SESSION_UPDATE`. */
        fun onMediaSessionIdReceived(mediaSessionId: String)

        /** `SPEAKING` for a remote user; also how their audio ssrc is learned. */
        fun onSpeaking(userId: Long, ssrc: Int, speaking: Boolean)

        /** `VIDEO` for a remote user. */
        fun onVideo(userId: Long, audioSsrc: Int, videoSsrc: Int, streams: List<Payloads.Stream>?)

        /** `CLIENT_DISCONNECT`. */
        fun onUserDisconnect(userId: Long)

        /** Gateway v8 announces all users already present in the DAVE session. */
        fun onUsersConnect(userIds: List<String>) = Unit

        fun onUserFlags(userId: String, flags: Int) = Unit

        fun onUserPlatform(userId: String, platform: Int) = Unit

        /** `MEDIA_SINK_WANTS`: per-stream encode quality the receivers are asking for. */
        fun onMediaSinkWants(wants: Map<String, Int>)

        /** The session ended. [wasFatal] means "do not expect this socket to come back". */
        fun onDisconnected(wasFatal: Boolean, code: Int?, reason: String?)
    }

    private val loggingTag = "$loggingTagPrefix->RtcControlSocket ${instanceCount.incrementAndGet()}"
    private val gson = Gson()
    private val timer = Timer("$loggingTag-timer", true)

    /**
     * Reconnect backoff. Deliberately jitter-free and capped at three failures: unlike the main
     * gateway, a voice socket that cannot come back within a few seconds is better torn down so the
     * caller can ask the gateway for a fresh voice server.
     */
    private val backoff = Backoff(
        minBackoffMs = 1_000L,
        maxBackoffMs = 5_000L,
        failureThreshold = 3,
        jitter = false,
        scheduler = Backoff.TimerScheduler(loggingTag, executor),
    )

    private val listeners = CopyOnWriteArraySet<Listener>()

    private var socket: WebSocket? = null
    private var state: State = State.DISCONNECTED

    /** Protocol version the server announced in `HELLO`; media sink wants need at least 5. */
    private var serverVersion: Int = 3

    private var serverId: String? = null
    private var sessionId: String? = null
    private var heartbeatIntervalMs: Long? = null
    private var heartbeatAcked: Boolean = false
    private var heartbeatTask: TimerTask? = null
    private var lastHeartbeatAckTime: Long? = null
    private var helloTimeoutTask: TimerTask? = null
    private var connectionStartTime: Long? = null
    private var resumable: Boolean = false
    private var sequence: Int = NO_SEQUENCE
    private var supportedModes: List<String> = emptyList()
    private var transportMode: String = TransportModes.XCHACHA20
    private var forceTransportFallback = false
    private var maxDaveProtocolVersion = MAX_DAVE_PROTOCOL_VERSION
    private var daveRecoveryAttempted = false

    private val daveSession = DaveSession(
        listener = object : DaveSession.Listener {
            override fun onDaveProtocolVersion(version: Int) = daveListener()?.onDaveProtocolVersion(version) ?: Unit
            override fun onDaveExternalSender(externalSender: ByteArray) =
                daveListener()?.onDaveExternalSender(externalSender) ?: Unit
            override fun onDavePrepareEpoch(epoch: Int, transitionId: Int, onKeyPackage: (ByteArray) -> Unit) =
                daveListener()?.onDavePrepareEpoch(epoch, transitionId, onKeyPackage) ?: Unit
            override fun onDaveRequestKeyPackage(onKeyPackage: (ByteArray) -> Unit) =
                daveListener()?.onDaveRequestKeyPackage(onKeyPackage) ?: Unit
            override fun onDaveProcessProposals(proposals: ByteArray, onCommitWelcome: (ByteArray) -> Unit) =
                daveListener()?.onDaveProcessProposals(proposals, onCommitWelcome) ?: Unit
            override fun onDavePrepareCommitTransition(
                transitionId: Int,
                commit: ByteArray,
                onProcessed: (Boolean, Int) -> Unit,
            ) = daveListener()?.onDavePrepareCommitTransition(transitionId, commit, onProcessed) ?: Unit
            override fun onDaveProcessWelcome(
                transitionId: Int,
                welcome: ByteArray,
                onProcessed: (Boolean, Int) -> Unit,
            ) = daveListener()?.onDaveProcessWelcome(transitionId, welcome, onProcessed) ?: Unit
            override fun onDavePrepareTransition(transitionId: Int, protocolVersion: Int, onPrepared: () -> Unit) =
                daveListener()?.onDavePrepareTransition(transitionId, protocolVersion, onPrepared) ?: Unit
            override fun onDaveExecuteTransition(transitionId: Int) =
                daveListener()?.onDaveExecuteTransition(transitionId) ?: Unit
        },
        sendJson = ::send,
        sendBinary = { opcode, payload -> sendBinary(opcode, payload) },
    )

    fun addListener(listener: Listener) {
        listeners += listener
    }

    /** Opens the socket. Returns `false` when one is already live. */
    fun connect(): Boolean {
        executor.assertOnEngineThread()
        if (state != State.DISCONNECTED) {
            warn("Cannot start a new connection, connection state is not disconnected")
            return false
        }
        state = State.CONNECTING
        openSocket()
        notifyListeners { it.onConnecting() }
        return true
    }

    /** Tears the socket down for good; the caller is not going to reconnect this one. */
    fun close() {
        executor.assertOnEngineThread()
        info("[CLOSE]")
        cleanUp { it.close(WS_NORMAL_CLOSURE, "Force Close") }
        clearSession()
        state = State.DISCONNECTED
        notifyListeners { it.onDisconnected(false, WS_NORMAL_CLOSURE, "Force Close") }
    }

    /** Sends `IDENTIFY` and moves into the authenticating state. */
    fun identify(
        serverId: String,
        userId: Long,
        sessionId: String,
        video: Boolean,
        streams: List<Payloads.Stream>,
        maxDaveProtocolVersion: Int = MAX_DAVE_PROTOCOL_VERSION,
    ) {
        executor.assertOnEngineThread()
        this.serverId = serverId
        this.sessionId = sessionId
        this.maxDaveProtocolVersion = if (daveRecoveryAttempted) {
            maxOf(maxDaveProtocolVersion, MAX_DAVE_PROTOCOL_VERSION)
        } else {
            maxDaveProtocolVersion
        }
        state = State.IDENTIFYING
        send(
            Opcodes.IDENTIFY,
            Payloads.Identify(
                serverId,
                userId.toString(),
                sessionId,
                token,
                video,
                streams,
                this.maxDaveProtocolVersion,
            ),
        )
    }

    /** Sends `SELECT_PROTOCOL` once the media engine has bound its transport. */
    fun selectProtocol(
        address: String,
        port: Int,
        codecs: List<Payloads.Protocol.CodecInfo>,
        engineModes: List<String>,
    ) {
        executor.assertOnEngineThread()
        transportMode = chooseTransportMode(engineModes)
        send(
            Opcodes.SELECT_PROTOCOL,
            Payloads.Protocol(
                protocol = TRANSPORT_PROTOCOL,
                data = Payloads.Protocol.ProtocolInfo(address, port, transportMode),
                codecs = codecs,
            ),
        )
    }

    fun sendSpeaking(ssrc: Int, speaking: Boolean) {
        executor.assertOnEngineThread()
        send(Opcodes.SPEAKING, Payloads.Speaking(ssrc, if (speaking) 1 else 0, 0))
    }

    fun sendVideo(audioSsrc: Int, videoSsrc: Int, rtxSsrc: Int, streams: List<Payloads.Stream>) {
        executor.assertOnEngineThread()
        send(Opcodes.VIDEO, Payloads.Video(audioSsrc, videoSsrc, rtxSsrc, null, streams))
    }

    /**
     * Publishes what this client wants to receive from each sender.
     *
     * Silently skipped on servers older than v5, which would reject the opcode.
     */
    fun sendMediaSinkWants(wants: Map<String, Int>) {
        executor.assertOnEngineThread()
        if (serverVersion >= MEDIA_SINK_WANTS_MIN_SERVER_VERSION) send(Opcodes.MEDIA_SINK_WANTS, wants)
    }

    /**
     * Brings the next heartbeat forward, used when the app returns to the foreground and wants to
     * know immediately whether the session is still alive.
     *
     * With no socket and [resetBackoffWhenDisconnected] set, a pending reconnect is instead pulled
     * forward — the user is looking at the screen, so waiting out the remaining backoff is worse
     * than retrying now.
     */
    fun requestImmediateHeartbeat(delayMs: Long, reason: String, resetBackoffWhenDisconnected: Boolean) {
        executor.assertOnEngineThread()
        when {
            socket != null -> {
                info("Performing an immediate heartbeat on existing socket: $reason")
                heartbeatTask?.cancel()
                heartbeatTask = scheduleHeartbeat(delayMs)
            }
            resetBackoffWhenDisconnected && backoff.isPending -> {
                info("Connection backoff reset Immediate heartbeat when socket was disconnected.")
                backoff.succeed()
                reconnect(false, WS_BACKOFF_RESET, "Reset backoff.")
            }
            else ->
                info("Immediate heartbeat requested, but is disconnected and a reset was not requested: $reason")
        }
    }

    /** Diagnostics snapshot. Deliberately carries no token, endpoint or key material. */
    fun describe(): Map<String, Any?> = mapOf(
        "hasServerId" to (serverId != null),
        "hasSessionId" to (sessionId != null),
        "heartbeatInterval" to heartbeatIntervalMs,
        "lastHeartbeatAckTime" to lastHeartbeatAckTime,
        "connectionStartTime" to connectionStartTime,
        "connectionState" to state.name,
        "resumable" to resumable,
        "serverVersion" to serverVersion,
    )

    // region Socket lifecycle

    private fun openSocket() {
        info("[CONNECT]")
        if (socket != null) {
            AppLog.e(loggingTag, "Connect called with already existing websocket")
            cleanUp { it.close(WS_NORMAL_CLOSURE, "Force Close") }
            return
        }

        connectionStartTime = clock.currentTimeMillis()
        helloTimeoutTask?.cancel()
        helloTimeoutTask = schedule(HELLO_TIMEOUT_MS) {
            handleClosed(true, null, "The connection timed out. Did not receive OP_HELLO in time.")
        }

        val factory = sslSocketFactory
        val clientBuilder = OkHttpClient.Builder()
            // The voice socket is long-lived and mostly idle between heartbeats; the default
            // 10 s read timeout would tear it down on a quiet channel.
            .readTimeout(SOCKET_READ_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (factory != null) clientBuilder.sslSocketFactory(factory, platformTrustManager())
        val client = clientBuilder.build()

        // `.gg` hosts are the gateway's public names; the media servers answer on `.media`, and a
        // plaintext endpoint arrives on :80 where the TLS listener is :443.
        val url = "$SECURE_SCHEME://${endpoint.replace(".gg", ".media")}"
            .replace(":80", ":443") + "?v=$WIRE_VERSION"
        info("attempting WSS connection")
        socket = client.newWebSocket(Request.Builder().url(url).build(), this)
    }

    /**
     * Handles a socket that went away, and decides whether to retry.
     *
     * The four fatal codes are authentication and session failures: retrying with the same token
     * would fail identically, so the caller has to go back to the gateway for a new voice server.
     */
    private fun handleClosed(wasFatal: Boolean, code: Int?, reason: String?) {
        state = State.DISCONNECTED
        if (VoiceCloseRecovery.from(code) != VoiceCloseRecovery.NONE && backoff.hasReachedFailureThreshold()) {
            disconnect(wasFatal, code, reason)
            return
        }
        when (VoiceCloseRecovery.from(code)) {
            VoiceCloseRecovery.RETRY_WITH_XCHACHA20 -> if (transportMode != TransportModes.XCHACHA20) {
                forceTransportFallback = true
                transportMode = TransportModes.XCHACHA20
                retryFreshSession(requireNotNull(code))
                return
            }
            VoiceCloseRecovery.RETRY_WITH_DAVE -> if (!daveRecoveryAttempted) {
                daveRecoveryAttempted = true
                maxDaveProtocolVersion = MAX_DAVE_PROTOCOL_VERSION
                daveSession.reset()
                retryFreshSession(requireNotNull(code))
                return
            }
            VoiceCloseRecovery.NONE -> Unit
        }
        if (code != null && code in FATAL_CLOSE_CODES) {
            disconnect(true, code, reason)
            return
        }
        if (backoff.hasReachedFailureThreshold()) {
            warn("[WS CLOSED] Backoff exceeded. Resetting.")
            disconnect(wasFatal, code, reason)
            return
        }
        cleanUp(null)
        val delaySeconds = backoff.fail { reconnect(wasFatal, code, reason) } / 1000.0
        warn("[WS CLOSED] fatal=$wasFatal code=$code; retrying in %.2f seconds.".format(delaySeconds))
    }

    private fun retryFreshSession(code: Int) {
        cleanUp(null)
        serverId = null
        sessionId = null
        resumable = false
        sequence = NO_SEQUENCE
        val delaySeconds = backoff.fail {
            state = State.CONNECTING
            openSocket()
        } / 1000.0
        warn("[WS CLOSED] code=$code; retrying fresh in %.2f seconds.".format(delaySeconds))
    }

    private fun reconnect(wasFatal: Boolean, code: Int?, reason: String?) {
        info("[RECONNECT] fatal=$wasFatal code=$code")
        cleanUp { it.close(WS_RECONNECT, "close reason") }
        state = State.RECONNECTING
        openSocket()
    }

    private fun disconnect(wasFatal: Boolean, code: Int?, reason: String?) {
        warn("[DISCONNECT] fatal=$wasFatal code=$code")
        cleanUp(null)
        clearSession()
        state = State.DISCONNECTED
        notifyListeners { it.onDisconnected(wasFatal, code, reason) }
    }

    /** Cancels every timer and drops the socket reference, optionally closing it first. */
    private fun cleanUp(closeAction: ((WebSocket) -> Unit)?) {
        backoff.cancel()
        heartbeatTask?.cancel()
        helloTimeoutTask?.cancel()
        socket?.let { closeAction?.invoke(it) }
        socket = null
    }

    private fun clearSession() {
        serverId = null
        sessionId = null
        resumable = false
        sequence = NO_SEQUENCE
        daveSession.reset()
    }

    // endregion

    // region Heartbeat

    /**
     * Sends a heartbeat, or gives up on the socket when the previous one was never acked.
     *
     * Gateway v8 echoes [Payloads.Heartbeat.t] and buffers events after `seq_ack` for resume.
     */
    private fun onHeartbeatInterval() {
        if (!heartbeatAcked) {
            cleanUp { it.close(WS_HEARTBEAT_TIMEOUT, "Heartbeat timeout.") }
            val delaySeconds =
                backoff.fail { reconnect(true, WS_HEARTBEAT_TIMEOUT, "Heartbeat timeout.") } / 1000.0
            warn("[ACK TIMEOUT] reconnecting in %.2f seconds.".format(delaySeconds))
            return
        }
        heartbeatAcked = false
        send(Opcodes.HEARTBEAT, Payloads.Heartbeat(clock.currentTimeMillis(), sequence))
        val interval = heartbeatIntervalMs
        if (interval == null) {
            warn("onHeartbeatInterval called when heartbeatInterval was null")
            return
        }
        heartbeatTask = scheduleHeartbeat(interval)
    }

    private fun scheduleHeartbeat(delayMs: Long): TimerTask = schedule(delayMs) { onHeartbeatInterval() }

    // endregion

    // region OkHttp callbacks

    override fun onOpen(webSocket: WebSocket, response: Response) {
        onEngineThread {
            if (webSocket !== socket) return@onEngineThread
            when (state) {
                State.CONNECTING -> notifyListeners { it.onSocketConnect() }
                State.RECONNECTING -> {
                    resumeOrDisconnect()
                    return@onEngineThread
                }
                else -> Unit
            }
            state = State.CONNECTED
            // The handshake round trip is a free first latency sample; halved because the open
            // completed one way.
            val elapsed = clock.currentTimeMillis() - (connectionStartTime ?: 0L)
            info("[CONNECTED]")
            notifyListeners { it.onHeartbeatAck(elapsed / 2) }
        }
    }

    /**
     * Decides between `RESUME` and a clean disconnect after the socket came back.
     *
     * Resuming needs the previous session *and* recent evidence the server still has it: a
     * heartbeat acked more than a minute ago means the session has almost certainly been reaped, and
     * a rejected resume costs more than a fresh identify.
     */
    private fun resumeOrDisconnect() {
        val session = sessionId
        val server = serverId
        val lastAck = lastHeartbeatAckTime
        val isHeartbeatRecentEnough = lastAck == null ||
            clock.currentTimeMillis() - lastAck <= RESUMABLE_HEARTBEAT_AGE_MS

        if (session != null && server != null && resumable && isHeartbeatRecentEnough) {
            info("[RESUME] resuming session")
            notifyListeners { it.onSocketResume() }
            state = State.RESUMING
            send(Opcodes.RESUME, Payloads.Resume(token, session, server, sequence))
        } else {
            disconnect(
                false,
                WS_CANNOT_RESUME,
                "Cannot resume connection. resumable: $resumable -- isHeartbeatRecentEnough: $isHeartbeatRecentEnough",
            )
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val message = try {
            VoiceGatewayFrameCodec.decodeText(gson, text)
        } catch (_: RuntimeException) {
            warn("discarding malformed text voice frame")
            return
        }
        AppLog.d(DAVE_LOG_TAG, "IN ${Opcodes.getNameOf(message.opcode)} ${traceText(message.opcode, text)}")
        AppLog.d(loggingTag, "received (${Opcodes.getNameOf(message.opcode)})")
        onEngineThread {
            if (webSocket !== socket) return@onEngineThread
            message.seq?.let { sequence = it }
            handleMessage(message)
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        onEngineThread {
            if (webSocket !== socket) return@onEngineThread
            try {
                val frame = VoiceGatewayFrameCodec.decodeBinary(bytes, serverVersion)
                AppLog.d(DAVE_LOG_TAG, "IN ${Opcodes.getNameOf(frame.opcode)} <binary:${frame.payload.size} bytes>")
                frame.sequence?.let { sequence = it }
                handleBinaryMessage(frame)
            } catch (_: RuntimeException) {
                warn("discarding malformed binary voice frame")
            }
        }
    }

    private fun handleMessage(message: Payloads.Incoming) {
        when (message.opcode) {
            Opcodes.READY -> {
                val ready = gson.fromJson(message.data, Payloads.Ready::class.java)
                daveSession.reset()
                supportedModes = ready.modes.orEmpty()
                transportMode = chooseTransportMode()
                backoff.succeed()
                val elapsed = clock.currentTimeMillis() - (connectionStartTime ?: 0L)
                info("[READY] took $elapsed ms")
                notifyListeners { it.onReady(ready.port, ready.ssrc, ready.ip, ready.streams, supportedModes) }
            }

            // The server may ask us to heartbeat out of band; answer immediately.
            Opcodes.HEARTBEAT ->
                send(Opcodes.HEARTBEAT, Payloads.Heartbeat(clock.currentTimeMillis(), sequence))

            Opcodes.SELECT_PROTOCOL_ACK -> {
                val description = gson.fromJson(message.data, Payloads.Description::class.java)
                notifyListeners { listener ->
                    listener.onSelectProtocolAck(description.mode, description.secretKey)
                    listener.onCodecs(description.audioCodec, description.videoCodec)
                    description.mediaSessionId?.let(listener::onMediaSessionIdReceived)
                }
                daveSession.onProtocolSelected(description.secureFramesVersion)
                // Only a session that reached this point has something worth resuming.
                resumable = true
            }

            Opcodes.SPEAKING -> {
                val speaking = gson.fromJson(message.data, Payloads.Speaking::class.java)
                val userId = speaking.userId
                val isSpeaking = speaking.speaking
                if (userId != null && isSpeaking != null) {
                    notifyListeners { it.onSpeaking(userId, speaking.ssrc, isSpeaking == 1) }
                }
            }

            Opcodes.HEARTBEAT_ACK -> {
                val sentAt = gson.fromJson(message.data, Payloads.Heartbeat::class.java).t
                val now = clock.currentTimeMillis()
                val ping = now - sentAt
                AppLog.d(loggingTag, "got heartbeat ack after $ping ms")
                lastHeartbeatAckTime = now
                heartbeatAcked = true
                notifyListeners { it.onHeartbeatAck(ping) }
            }

            Opcodes.HELLO -> {
                val hello = gson.fromJson(message.data, Payloads.Hello::class.java)
                helloTimeoutTask?.cancel()
                info("[HELLO] version=${hello.serverVersion}")
                heartbeatIntervalMs = hello.heartbeatIntervalMs
                serverVersion = hello.serverVersion
                heartbeatTask?.cancel()
                // Seeded as acked so the first interval sends rather than declaring a timeout.
                heartbeatAcked = true
                onHeartbeatInterval()
            }

            Opcodes.RESUMED -> backoff.succeed()

            Opcodes.CLIENTS_CONNECT -> {
                val connected = gson.fromJson(message.data, Payloads.ClientsConnect::class.java)
                notifyListeners { it.onUsersConnect(connected.userIds) }
            }

            Opcodes.VIDEO -> {
                val video = gson.fromJson(message.data, Payloads.Video::class.java)
                val userId = video.userId
                if (userId != null) {
                    notifyListeners { it.onVideo(userId, video.audioSsrc, video.videoSsrc, video.streams) }
                }
            }

            Opcodes.CLIENT_DISCONNECT -> {
                val disconnect = gson.fromJson(message.data, Payloads.ClientDisconnect::class.java)
                notifyListeners { it.onUserDisconnect(disconnect.userId) }
            }

            Opcodes.CLIENT_FLAGS -> {
                val flags = gson.fromJson(message.data, Payloads.ClientFlags::class.java)
                notifyListeners { it.onUserFlags(flags.userId, flags.flags) }
            }

            Opcodes.CLIENT_PLATFORM -> {
                val platform = gson.fromJson(message.data, Payloads.ClientPlatform::class.java)
                notifyListeners { it.onUserPlatform(platform.userId, platform.platform) }
            }

            Opcodes.DAVE_PREPARE_TRANSITION -> {
                val transition = gson.fromJson(message.data, Payloads.DavePrepareTransition::class.java)
                daveSession.onPrepareTransition(transition.protocolVersion, transition.transitionId)
            }

            Opcodes.DAVE_EXECUTE_TRANSITION -> {
                val transition = gson.fromJson(message.data, Payloads.DaveExecuteTransition::class.java)
                daveSession.onExecuteTransition(transition.transitionId)
            }

            Opcodes.DAVE_PREPARE_EPOCH -> {
                val epoch = gson.fromJson(message.data, Payloads.DavePrepareEpoch::class.java)
                daveSession.onPrepareEpoch(epoch.protocolVersion, epoch.epoch, epoch.transitionId)
            }

            Opcodes.SESSION_UPDATE -> {
                val update = gson.fromJson(message.data, Payloads.SessionUpdate::class.java)
                update.mediaSessionId?.let { id -> notifyListeners { it.onMediaSessionIdReceived(id) } }
            }

            Opcodes.MEDIA_SINK_WANTS -> {
                val wants: Map<String, Int> = gson.fromJson(message.data, MEDIA_SINK_WANTS_TYPE)
                notifyListeners { it.onMediaSinkWants(wants) }
            }

            else -> info("unknown opcode: ${message.opcode}")
        }
    }

    private fun handleBinaryMessage(frame: VoiceGatewayFrameCodec.BinaryFrame) {
        when (frame.opcode) {
            Opcodes.DAVE_MLS_EXTERNAL_SENDER -> daveSession.onExternalSender(frame.payload)
            Opcodes.DAVE_MLS_PROPOSALS -> daveSession.onProposals(frame.payload)
            Opcodes.DAVE_MLS_ANNOUNCE_COMMIT_TRANSITION -> {
                val reader = com.discord.rtcconnection.dave.ByteReader(frame.payload)
                daveSession.onAnnounceCommitTransition(reader.readUint16(), reader.collect())
            }
            Opcodes.DAVE_MLS_WELCOME -> {
                val reader = com.discord.rtcconnection.dave.ByteReader(frame.payload)
                daveSession.onWelcome(reader.readUint16(), reader.collect())
            }
            else -> info("unknown binary opcode: ${frame.opcode}")
        }
    }

    private fun chooseTransportMode(engineModes: List<String> = emptyList()): String =
        TransportModes.select(supportedModes, engineModes, forceTransportFallback)

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onEngineThread {
        if (webSocket === socket) handleClosed(true, code, reason)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        // Complete the closing handshake so the server sees a clean shutdown.
        webSocket.close(code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onEngineThread {
        if (webSocket === socket) {
            handleClosed(true, null, "Voice WebSocket failure")
        }
    }

    // endregion

    private fun send(opcode: Int, data: Any) {
        val webSocket = socket
        if (webSocket == null) {
            AppLog.e(loggingTag, "opcode() websocket null. opcode: ${Opcodes.getNameOf(opcode)}")
            return
        }
        try {
            val json = gson.toJson(Payloads.Outgoing(opcode, data))
            AppLog.d(DAVE_LOG_TAG, "OUT ${Opcodes.getNameOf(opcode)} ${traceText(opcode, json)}")
            AppLog.d(loggingTag, "sending (${Opcodes.getNameOf(opcode)})")
            webSocket.send(json)
        } catch (e: Exception) {
            warn("exception sending opcode: $opcode")
        }
    }

    private fun sendBinary(opcode: Int, data: ByteArray): Boolean {
        val webSocket = socket
        if (webSocket == null) {
            AppLog.e(loggingTag, "opcode() websocket null. opcode: ${Opcodes.getNameOf(opcode)}")
            return false
        }
        return try {
            AppLog.d(DAVE_LOG_TAG, "OUT ${Opcodes.getNameOf(opcode)} <binary:${data.size} bytes>")
            AppLog.d(loggingTag, "sending binary (${Opcodes.getNameOf(opcode)})")
            webSocket.send(VoiceGatewayFrameCodec.encodeBinary(opcode, data))
        } catch (_: Exception) {
            warn("exception sending binary opcode: $opcode")
            false
        }
    }

    private fun daveListener(): Listener? = listeners.firstOrNull()

    private inline fun notifyListeners(block: (Listener) -> Unit) {
        executor.assertOnEngineThread()
        listeners.forEach(block)
    }

    @AnyThread
    private fun onEngineThread(action: () -> Unit) {
        executor.run(action)
    }

    private fun schedule(delayMs: Long, action: () -> Unit): TimerTask {
        val task = object : TimerTask() {
            override fun run() = onEngineThread(action)
        }
        timer.schedule(task, delayMs)
        return task
    }

    private fun info(message: String) = AppLog.i(loggingTag, message)

    private fun warn(message: String) = AppLog.w(loggingTag, message)

    /**
     * The platform's default trust managers, so supplying a custom [SSLSocketFactory] does not
     * silently drop certificate validation.
     */
    private fun platformTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    companion object {
        private const val DAVE_LOG_TAG = "DaveWebSocket"

        private val REDACTED_TRACE_OPCODES = setOf(Opcodes.IDENTIFY, Opcodes.RESUME, Opcodes.SELECT_PROTOCOL_ACK)

        /** Called on every inbound/outbound voice-gateway frame, so the opcode set is hoisted. */
        internal fun traceText(opcode: Int, text: String): String =
            if (opcode in REDACTED_TRACE_OPCODES) {
                "<redacted:${text.length} chars>"
            } else {
                text
            }

        private val instanceCount = AtomicInteger()

        private val MEDIA_SINK_WANTS_TYPE = object : TypeToken<Map<String, Int>>() {}.type

        private const val SECURE_SCHEME = "wss"
        private const val WIRE_VERSION = 8
        private const val MEDIA_SINK_WANTS_MIN_SERVER_VERSION = 5
        private const val TRANSPORT_PROTOCOL = "udp"
        private const val MAX_DAVE_PROTOCOL_VERSION = 1
        private const val NO_SEQUENCE = -1

        private const val HELLO_TIMEOUT_MS = 20_000L
        private const val RESUMABLE_HEARTBEAT_AGE_MS = 60_000L
        private const val SOCKET_READ_TIMEOUT_MINUTES = 1L

        private const val WS_NORMAL_CLOSURE = 1000
        private const val WS_RECONNECT = 4000
        private const val WS_HEARTBEAT_TIMEOUT = 4800
        private const val WS_CANNOT_RESUME = 4801
        private const val WS_BACKOFF_RESET = 4802

        /**
         * Close codes that mean the session itself is unusable: bad authentication, invalid or
         * expired session, and server-side termination.
         */
        private val FATAL_CLOSE_CODES = setOf(4004, 4006, 4011, 4015)
    }
}
