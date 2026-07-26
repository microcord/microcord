package com.discord.rtcconnection

import android.content.Intent
import androidx.annotation.AnyThread
import co.discord.media_engine.MediaType
import co.discord.media_engine.StreamParameters
import co.discord.media_engine.VoiceQuality
import com.discord.app.AppLog
import com.discord.rtcconnection.mediaengine.MediaEngine
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.rtcconnection.mediaengine.ThumbnailEmitter
import com.discord.rtcconnection.socket.RtcControlSocket
import com.discord.rtcconnection.socket.io.Payloads
import com.discord.rtcconnection.stats.RtcStatsCollector
import com.discord.rtcconnection.stats.VideoQualityTracker
import com.discord.utilities.networking.Backoff
import com.discord.utilities.networking.NetworkMonitor
import com.discord.utilities.time.Clock
import java.net.URI
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocketFactory
import kotlin.math.roundToInt
import rx.Observable
import rx.Subscription
import rx.subjects.BehaviorSubject

/**
 * One voice session: the control socket, the media transport, and the state machine that keeps the
 * two in step.
 *
 * The handshake runs in this order, and each step is driven by the previous one's callback rather
 * than by a timer:
 *
 * 1. [connect] waits for the media engine to be prepared, then opens [RtcControlSocket].
 * 2. The socket opens → `IDENTIFY` → [State.Authenticating].
 * 3. `READY` names a dedicated media server → [MediaEngine.connect] opens the UDP transport →
 *    [State.RtcConnecting].
 * 4. The engine reports its local address → `SELECT_PROTOCOL`.
 * 5. `SELECT_PROTOCOL_ACK` carries the encryption key and codecs → the engine starts sending →
 *    [State.RtcConnected].
 *
 * Two things can fail independently and they are handled differently. Losing the *socket* is
 * usually recoverable, so [Backoff] schedules a reconnect unless the close was clean or the network
 * has been down long enough that the voice server has certainly dropped the session. Losing the
 * *transport* is reported by the engine and reconnects through the same path. A fatal close — bad
 * token, dead session — stops retrying and tells the owner to go back to the gateway.
 *
 * Everything below the public API happens on the media-engine thread; [runOnEngineThread] is the
 * only entry point that does not assume it.
 *
 * Reference: `com.discord.rtcconnection.RtcConnection`. Its `State` subclass names were erased by
 * R8; the names used here are inferred from each state's role and the exact transitions are
 * recovered from `b.a.q.h0` / `b.a.q.b0`.
 */
class RtcConnection(
    val guildId: Long?,
    val channelId: Long,
    var sessionId: String,
    private val isVideoEnabled: Boolean,
    private val rtcServerId: String,
    private val userId: Long,
    private val mediaEngine: MediaEngine,
    private val clock: Clock,
    private val rtcConnectionType: Type = Type.Default,
    networkMonitor: NetworkMonitor,
    /** Locally muted users, shared with the owning store so a reconnect re-applies them. */
    private val mutedUsers: MutableMap<Long, Boolean> = LinkedHashMap(),
    /** Per-user playout volume, same lifetime as [mutedUsers]. */
    private val userVolumes: MutableMap<Long, Float> = LinkedHashMap(),
    /** Media session of the voice connection a Go Live stream belongs to. */
    private val parentMediaSessionId: String? = null,
    loggingTagPrefix: String,
    val streamKey: String? = null,
) {

    /** What this connection is for. A Go Live broadcast is a second connection to the same channel. */
    sealed class Type {
        object Default : Type()
        data class Stream(val senderId: Long) : Type()
    }

    /**
     * Handshake progress.
     *
     * The first four are the control socket's; the last four mirror
     * [MediaEngineConnection.ConnectionState] and describe the media transport.
     */
    sealed class State {
        /** No session. [willReconnect] is `false` for a deliberate or fatal disconnect. */
        data class Disconnected(val willReconnect: Boolean) : State()

        /** The gateway has not told us which voice server to use yet. */
        object AwaitingEndpoint : State()

        /** `IDENTIFY` sent, waiting for `READY`. */
        object Authenticating : State()

        /** The control socket is opening. */
        object Connecting : State()

        /** The media transport is down while the control socket is up. */
        object RtcDisconnected : State()

        /** The media transport is being opened. */
        object RtcConnecting : State()

        /** Media is flowing. */
        object RtcConnected : State()

        /** The engine could not find any route to the media server. */
        object NoRoute : State()

        /** [Disconnected] is a data class and keeps its generated `toString`. */
        override fun toString(): String = javaClass.simpleName
    }

    data class StateChange(val state: State, val metadata: Metadata?)

    /** Identity of a live connection, as the stores and UI refer to it. */
    data class Metadata(
        val rtcConnectionId: String,
        val mediaSessionId: String?,
        val channelId: Long?,
        val guildId: Long?,
        val streamKey: String?,
    )

    /** Coarse connection health, derived from round-trip time. */
    enum class Quality {
        UNKNOWN,
        BAD,
        AVERAGE,
        FINE,
        ;

        companion object {
            fun fromPing(pingMs: Double): Quality = when {
                pingMs.isNaN() -> UNKNOWN
                pingMs < FINE_PING_MS -> FINE
                pingMs < AVERAGE_PING_MS -> AVERAGE
                else -> BAD
            }

            private const val FINE_PING_MS = 250
            private const val AVERAGE_PING_MS = 500
        }
    }

    enum class AnalyticsEvent {
        VOICE_CONNECTION_SUCCESS,
        VOICE_CONNECTION_FAILURE,
        VOICE_DISCONNECT,
        VIDEO_STREAM_ENDED,
        MEDIA_SESSION_JOINED,
    }

    interface Listener {
        fun onAnalyticsEvent(event: AnalyticsEvent, properties: Map<String, Any>)

        /** The session cannot be retried; the owner must ask the gateway for a new voice server. */
        fun onFatalClose()

        fun onMediaEngineConnectionConnected(connection: RtcConnection)
        fun onMediaSessionIdReceived()
        fun onQualityUpdate(quality: Quality)
        fun onSpeaking(userId: Long, speaking: Boolean)
        fun onStateChange(stateChange: StateChange)

        /** A peer was announced to the engine; the UI can start rendering a tile for them. */
        fun onUserCreated(connection: RtcConnection, userId: Long)

        fun onVideoMetadata(metadata: VideoMetadata)
        fun onVideoStream(userId: Long, streamId: Int?, audioSsrc: Int, videoSsrc: Int, rtxSsrc: Int)
    }

    abstract class DefaultListener : Listener {
        override fun onAnalyticsEvent(event: AnalyticsEvent, properties: Map<String, Any>) = Unit
        override fun onFatalClose() = Unit
        override fun onMediaEngineConnectionConnected(connection: RtcConnection) = Unit
        override fun onMediaSessionIdReceived() = Unit
        override fun onQualityUpdate(quality: Quality) = Unit
        override fun onSpeaking(userId: Long, speaking: Boolean) = Unit
        abstract override fun onStateChange(stateChange: StateChange)
        override fun onUserCreated(connection: RtcConnection, userId: Long) = Unit
        override fun onVideoMetadata(metadata: VideoMetadata) = Unit
        override fun onVideoStream(userId: Long, streamId: Int?, audioSsrc: Int, videoSsrc: Int, rtxSsrc: Int) = Unit
    }

    private val loggingTag = "$loggingTagPrefix->RtcConnection ${instanceCount.incrementAndGet()}"

    /** Stable identity for this attempt, quoted in every analytics event. */
    val id: String = UUID.randomUUID().toString()

    private val listeners = CopyOnWriteArrayList<Listener>()

    /**
     * Reconnect pacing for the *media* side. Jittered and uncapped in failure count: unlike the
     * control socket, a transport that keeps failing is still worth retrying while the user is in
     * the channel.
     */
    private val reconnectBackoff = Backoff(minBackoffMs = 1_000L, maxBackoffMs = 10_000L)

    private val videoQuality = VideoQualityTracker(clock)

    private var socket: RtcControlSocket? = null
    private var mediaEngineConnection: MediaEngineConnection? = null
    private var statsCollector: RtcStatsCollector? = null
    private var transportInfo: MediaEngineConnection.TransportInfo? = null

    private var connectionStateChange = StateChange(State.Disconnected(false), null)
    private val connectionStateSubject = BehaviorSubject.create(connectionStateChange)

    private var destroyed = false
    private var sentVideo = false
    private var hasConnected = false

    /** Last five round-trip samples, for the disconnect report's `ping_average`. */
    private val pings = mutableListOf<Long>()
    private var pingBadCount = 0

    private var connectStartTime: Long? = null
    private var connectCompletedTime: Long? = null
    private var connectCount = 0

    /** When connectivity was lost, or `null` while online. */
    private var networkLossTime: Long? = null

    private var mediaEnginePrepareSubscription: Subscription? = null
    private var networkSubscription: Subscription? = null

    private var hostname: String? = null
    private var port: Int? = null

    var mediaSessionId: String? = null
        private set

    /** Our own outbound video ssrc; `0` while not sending video. */
    private var videoSsrc: Long = 0

    private val socketListener = SocketListener()
    private val mediaEngineConnectionListener = EngineConnectionListener()

    init {
        breadcrumb("Created RtcConnection. GuildID: $guildId ChannelID: $channelId")
        // `skip(1)` drops the current value: only a *change* in connectivity is interesting, and the
        // seeded value would otherwise fire a spurious heartbeat request during construction.
        networkSubscription = networkMonitor.observeIsConnected()
            .skip(1)
            .subscribe(
                { isConnected -> handleConnectivityChange(isConnected) },
                { error -> logError("failed to handle connectivity change in $loggingTag", error) },
            )
        listeners += object : DefaultListener() {
            override fun onStateChange(stateChange: StateChange) = connectionStateSubject.onNext(stateChange)
        }
    }

    // region Public API

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun getConnectionState(): Observable<StateChange> = connectionStateSubject

    fun getMetadata(): Metadata = Metadata(id, mediaSessionId, channelId, guildId, streamKey)

    /**
     * Opens the session against [endpoint] with [token].
     *
     * An empty [endpoint] is not an error: the gateway sends `VOICE_SERVER_UPDATE` with no endpoint
     * while it is moving the session between servers, and the correct response is to sit in
     * [State.AwaitingEndpoint] until the real one arrives.
     */
    @AnyThread
    fun connect(endpoint: String?, token: String?, sslSocketFactory: SSLSocketFactory? = null) =
        runOnEngineThread {
            connectStartTime = clock.currentTimeMillis()
            connectCount++
            mediaEnginePrepareSubscription = mediaEngine.prepare()
                .timeout(MEDIA_ENGINE_PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .subscribe(
                    { runOnEngineThread { openSocket(endpoint, token, sslSocketFactory) } },
                    { error -> handleFatalClose(false, "Failed to prepare media engine.", error) },
                )
        }

    @AnyThread
    fun disconnect() = runOnEngineThread { destroy("Force Close") }

    /** Sets a peer's playout volume as a percentage; `100` is unity. */
    fun setUserOutputVolume(userId: Long, volume: Float) {
        userVolumes[userId] = volume
        mediaEngineConnection?.setUserPlayoutVolume(userId, volume)
    }

    /**
     * Locally mutes a peer.
     *
     * The decision is remembered in [mutedUsers] as well as pushed to the engine, so a peer
     * re-announced after a reconnect comes back muted rather than briefly audible.
     *
     * (The reference mutates the connection's map from `StoreRtcConnection` directly; this keeps
     * the same behaviour behind a method.)
     */
    fun setUserMuted(userId: Long, muted: Boolean) {
        mutedUsers[userId] = muted
        mediaEngineConnection?.setUserMuted(userId, muted)
    }

    /** Marks a peer's video tile as scrolled out of view. */
    fun setUserOffScreen(userId: Long, offScreen: Boolean) {
        val connection = mediaEngineConnection
        if (connection == null) {
            AppLog.e(TAG, "MediaEngine not connected for setLocalVideoOffscreen.")
            return
        }
        connection.setUserOffScreen(userId, offScreen)
    }

    /**
     * Starts or stops the screen share on a [Type.Stream] connection.
     *
     * A `null` [intent] stops it. Called on the default connection this is a no-op rather than an
     * error, because the caller does not always know which connection it holds.
     */
    fun setScreenshare(intent: Intent?, thumbnailEmitter: ThumbnailEmitter? = null) {
        if (rtcConnectionType !is Type.Stream) return
        breadcrumb("Setting screenshare ${intent != null}")
        val connection = mediaEngineConnection
        if (connection == null) {
            AppLog.e(TAG, "MediaEngine not connected for setScreenshare.")
            return
        }
        if (intent != null) connection.startScreenshare(intent, thumbnailEmitter) else connection.stopScreenshare()
    }

    /** Diagnostics snapshot. Carries no token, endpoint, or user content. */
    fun describe(): Map<String, Any?> = mapOf(
        "id" to id,
        "hasMediaSessionId" to (mediaSessionId != null),
        "hasParentMediaSessionId" to (parentMediaSessionId != null),
        "channelId" to channelId,
        "guildId" to guildId,
        "hasStreamKey" to (streamKey != null),
        "isVideoEnabled" to isVideoEnabled,
        "rtcConnectionType" to rtcConnectionType.toString(),
        "state" to connectionStateChange.state.toString(),
        "connectCount" to connectCount,
        "pingBadCount" to pingBadCount,
        "sentVideo" to sentVideo,
        "videoSsrc" to videoSsrc,
        "socket" to socket?.describe(),
    )

    // endregion

    // region Connect / destroy

    /**
     * Builds the control socket once the engine is ready.
     *
     * `.gg` host names belong to the gateway; the media servers answer the same name under
     * `.media`. That rewrite happens here rather than in the socket so the parsed [hostname] and
     * [port] recorded for analytics describe the host actually dialled.
     */
    private fun openSocket(endpoint: String?, token: String?, sslSocketFactory: SSLSocketFactory?) {
        reconnectBackoff.succeed()

        if (endpoint.isNullOrEmpty()) {
            socket?.close()
            setState(State.AwaitingEndpoint)
            return
        }

        val mediaEndpoint = endpoint.replace(".gg", ".media")
        val scheme = if (sslSocketFactory != null) SECURE_SCHEME else INSECURE_SCHEME
        socket?.close()

        when {
            destroyed -> {
                handleFatalClose(false, "Connect called on destroyed instance.", logAsError = false)
                return
            }
            token == null -> {
                handleFatalClose(false, "Connect called with no token.")
                return
            }
        }

        // Deliberately does not record the endpoint or the token: a breadcrumb ends up in crash
        // reports, and either one identifies the account's live voice session.
        breadcrumb("connecting via voice endpoint")

        try {
            val uri = URI("$scheme://$mediaEndpoint")
            hostname = uri.host
            port = uri.port
        } catch (e: Exception) {
            logError("Failed to parse RTC endpoint", e)
        }

        socket = RtcControlSocket(
            endpoint = mediaEndpoint,
            token = requireNotNull(token),
            sslSocketFactory = sslSocketFactory,
            executor = mediaEngine.mediaEngineThreadExecutor,
            clock = clock,
            loggingTagPrefix = loggingTag,
        ).apply {
            addListener(socketListener)
            connect()
        }
    }

    /**
     * Ends the attempt.
     *
     * [willReconnect] chooses between "retry through [reconnect]" and "tell the owner this session
     * is over"; the latter also destroys the connection, because nothing else will.
     */
    private fun handleFatalClose(
        willReconnect: Boolean,
        reason: String,
        throwable: Throwable? = null,
        logAsError: Boolean = true,
    ) {
        if (logAsError) logError(reason, throwable) else AppLog.i(loggingTag, reason, throwable)

        statsCollector?.stop()
        statsCollector = null
        mediaEngineConnection?.destroy()
        mediaEngineConnection = null
        sentVideo = false
        reconnectBackoff.cancel()
        setState(State.Disconnected(willReconnect))

        if (willReconnect) {
            reconnect()
        } else {
            listeners.forEach(Listener::onFatalClose)
            destroy(reason)
        }
    }

    /**
     * Tears everything down for good.
     *
     * The disconnect analytics are emitted *before* the state flips, because the report quotes the
     * live stats; after [State.Disconnected] there is nothing left to report.
     */
    private fun destroy(reason: String) {
        reconnectBackoff.cancel()
        socket?.close()
        socket = null
        videoQuality.stop()

        if (connectionStateChange.state !is State.Disconnected) {
            reportVoiceDisconnect(willReconnect = false, reason = reason)
            reportVideoStreamsEnded()
        }

        mediaEnginePrepareSubscription?.unsubscribe()
        mediaEnginePrepareSubscription = null
        networkSubscription?.unsubscribe()
        networkSubscription = null
        statsCollector?.stop()
        statsCollector = null
        mediaEngineConnection?.destroy()
        mediaEngineConnection = null

        setState(State.Disconnected(false))
        breadcrumb("Destroy internal RTC connection: $reason")
        listeners.clear()
        sentVideo = false
        destroyed = true
    }

    /**
     * Re-runs the handshake on the existing socket.
     *
     * [connectStartTime] is only reset once a connection has succeeded at least once, so the
     * `connect_time` reported for the *first* success measures the whole attempt including retries.
     */
    private fun reconnect() {
        AppLog.recordBreadcrumb("reconnect", loggingTag)
        if (hasConnected) connectStartTime = clock.currentTimeMillis()
        connectCount++
        socket?.let {
            it.close()
            it.connect()
        }
    }

    /**
     * Connectivity came or went.
     *
     * Going online pulls the next heartbeat forward hard (5 s) and resets a pending reconnect,
     * because the socket is almost certainly dead and waiting out the backoff wastes the moment the
     * user is looking at the screen. Going offline only relaxes the heartbeat to 15 s: the radio may
     * come back before the session times out.
     */
    private fun handleConnectivityChange(isConnected: Boolean) {
        runOnEngineThread {
            val currentSocket = socket
            if (currentSocket == null) {
                logError("handleConnectivityChange() socket was null.")
            } else if (isConnected) {
                currentSocket.requestImmediateHeartbeat(
                    ONLINE_HEARTBEAT_DELAY_MS,
                    "network detected online",
                    resetBackoffWhenDisconnected = true,
                )
            } else {
                currentSocket.requestImmediateHeartbeat(
                    OFFLINE_HEARTBEAT_DELAY_MS,
                    "network detected offline",
                    resetBackoffWhenDisconnected = false,
                )
            }
        }
        networkLossTime = if (isConnected) null else clock.currentTimeMillis()
    }

    // endregion

    // region Control socket

    private inner class SocketListener : RtcControlSocket.Listener {

        override fun onConnecting() {
            reconnectBackoff.cancel()
            breadcrumb("Connecting to RTC server")
            setState(State.Connecting)
        }

        override fun onSocketConnect() {
            reconnectBackoff.cancel()
            AppLog.recordBreadcrumb("Connected to RTC server.", loggingTag)
            val currentSocket = socket
            if (currentSocket == null) {
                logError("onSocketConnect() socket was null.")
                return
            }
            currentSocket.identify(
                serverId = rtcServerId,
                userId = userId,
                sessionId = sessionId,
                video = isVideoEnabled,
                // A single "100" video layer is offered up front so the server allocates video
                // ssrcs even when the camera is off; turning it on later then needs no renegotiation.
                streams = listOf(Payloads.Stream(type = "video", rid = "100", quality = 100)),
            )
            setState(State.Authenticating)
        }

        override fun onSocketResume() = Unit

        override fun onHeartbeatAck(pingMs: Long) {
            pings += pingMs
            if (pings.size > MAX_PING_SAMPLES) pings.removeAt(0)
            if (pingMs > BAD_PING_MS) pingBadCount++
            val quality = Quality.fromPing(pingMs.toDouble())
            listeners.forEach { it.onQualityUpdate(quality) }
        }

        override fun onReady(port: Int, ssrc: Int, ip: String, streams: List<Payloads.Stream>) {
            breadcrumb("Discovered dedicated UDP server on port $port")
            setState(State.RtcConnecting)

            val streamParameters = streams.map { it.toStreamParameters() }
                .ifEmpty {
                    // A server that offers no layers still expects a video ssrc pair derived from
                    // the audio ssrc, so the client synthesises the default one.
                    listOf(
                        StreamParameters(
                            type = MediaType.Video,
                            rid = "100",
                            ssrc = ssrc + 1,
                            rtxSsrc = ssrc + 2,
                            active = false,
                            maxBitrate = 0,
                            quality = 100,
                            maxPixelCount = 0,
                        ),
                    )
                }

            val connection = mediaEngine.connect(
                userId = userId,
                options = MediaEngine.ConnectionOptions(ssrc, ip, port, streamParameters),
                type = when (rtcConnectionType) {
                    is Type.Default -> MediaEngineConnection.Type.DEFAULT
                    is Type.Stream -> MediaEngineConnection.Type.STREAM
                },
                onFailure = { error ->
                    handleFatalClose(true, "Error occurred while connecting to RTC server: ${error.message}.")
                },
            )
            if (connection == null) {
                logError("onSocketReady(): connect() returned null.")
                return
            }
            connection.addListener(mediaEngineConnectionListener)
            mediaEngineConnection = connection
        }

        override fun onSelectProtocolAck(mode: String, secretKey: List<Int>) {
            mediaEngineConnection?.setEncryptionSettings(mode, secretKey.toIntArray())
        }

        override fun onCodecs(audioCodec: String, videoCodec: String) {
            mediaEngineConnection?.selectCodecs(audioCodec, videoCodec)
        }

        override fun onMediaSessionIdReceived(mediaSessionId: String) {
            this@RtcConnection.mediaSessionId = mediaSessionId
            listeners.forEach(Listener::onMediaSessionIdReceived)
            emitAnalyticsEvent(AnalyticsEvent.MEDIA_SESSION_JOINED, mutableMapOf())
        }

        override fun onSpeaking(userId: Long, ssrc: Int, speaking: Boolean) {
            // Our own speaking state comes from the engine, not from the server echo.
            if (userId == this@RtcConnection.userId) return
            mediaEngineConnection?.connectUser(
                userId = userId,
                audioSsrc = ssrc,
                videoSsrc = null,
                isMuted = isUserMuted(userId),
                volume = userVolume(userId),
            )
            listeners.forEach { it.onUserCreated(this@RtcConnection, userId) }
        }

        override fun onVideo(userId: Long, audioSsrc: Int, videoSsrc: Int, streams: List<Payloads.Stream>?) {
            videoQuality.onVideoStreamStarted(videoSsrc.toLong())
            if (userId == this@RtcConnection.userId) return

            val metadata = streams
                ?.firstOrNull { it.ssrc == videoSsrc && it.maxResolution != null }
                ?.let { stream ->
                    val resolution = requireNotNull(stream.maxResolution)
                    VideoMetadata(userId, resolution.width, resolution.height, stream.maxFrameRate, resolution.type)
                }
            announceUserVideo(userId, audioSsrc, videoSsrc.toLong(), metadata)
        }

        override fun onUserDisconnect(userId: Long) {
            if (rtcConnectionType is Type.Default) {
                videoQuality.inboundReport(userId.toString())
                    ?.let { report -> reportVideoStreamEnded(userId, report, RECEIVER) }
            }
        }

        override fun onMediaSinkWants(wants: Map<String, Int>) {
            // Encode-quality negotiation is only active when a MediaSinkWantsManager is attached;
            // that component is not reconstructed yet, so the wants are recorded and ignored.
            AppLog.i(loggingTag, "remote MediaSinkWants: ${wants.size} entries")
        }

        /**
         * The socket ended.
         *
         * A clean 1000, or a network outage older than 30 s, means the voice server has forgotten
         * the session; anything else is worth one more attempt on the same endpoint.
         */
        override fun onDisconnected(wasFatal: Boolean, code: Int?, reason: String?) {
            breadcrumb("Disconnected from RTC server. wasFatal: $wasFatal -- code: $code -- reason: $reason")

            mediaEngineConnection?.destroy()
            mediaEngineConnection?.removeListener(mediaEngineConnectionListener)

            val offlineForMs = networkLossTime?.let { clock.currentTimeMillis() - it } ?: 0L
            val willReconnect = !(code == WS_NORMAL_CLOSURE || offlineForMs > MAX_OFFLINE_RESUME_MS)

            if (connectionStateChange.state !is State.Disconnected) {
                reportVoiceDisconnect(willReconnect, reason)
                statsCollector?.stop()
                statsCollector = null
                sentVideo = false
            }

            pingBadCount = 0
            connectCompletedTime = null
            setState(State.Disconnected(willReconnect))

            if (willReconnect) {
                val delayMs = reconnectBackoff.fail { runOnEngineThread { reconnect() } }
                AppLog.w(
                    loggingTag,
                    "Disconnect was not clean! Reason: $reason, code: $code. " +
                        "Reconnecting in ${delayMs / 1000} seconds.",
                )
            }
        }
    }

    // endregion

    // region Media engine transport

    private inner class EngineConnectionListener : MediaEngineConnection.Listener {

        /**
         * The engine bound its local transport; the voice server now needs to know where it is.
         *
         * Only UDP is usable: the reference treats a TCP fallback as fatal rather than trying to
         * carry RTP over it.
         */
        override fun onConnected(
            connection: MediaEngineConnection,
            transportInfo: MediaEngineConnection.TransportInfo,
            supportedVideoCodecs: List<Codec>,
        ) {
            reconnectBackoff.succeed()
            this@RtcConnection.transportInfo = transportInfo

            mediaEngineConnection?.let { engineConnection ->
                statsCollector?.stop()
                statsCollector = RtcStatsCollector(
                    connection = engineConnection,
                    voiceQuality = VoiceQuality(),
                    videoQuality = videoQuality,
                    krispOveruseDetector = KrispOveruseDetector(engineConnection),
                ).apply { start() }
                sentVideo = false
            }

            if (transportInfo.protocol != MediaEngineConnection.TransportInfo.Protocol.UDP) {
                handleFatalClose(true, "Unsupported protocol: ${transportInfo.protocol}.")
            } else {
                AppLog.recordBreadcrumb("Sending UDP info to RTC server.", loggingTag)
                val currentSocket = socket
                if (currentSocket == null) {
                    logError("onEngineConnectionConnected() socket was null.")
                    return
                }
                currentSocket.selectProtocol(
                    address = transportInfo.address,
                    port = transportInfo.port,
                    codecs = supportedVideoCodecs.map {
                        Payloads.Protocol.CodecInfo(it.name, it.priority, it.type, it.payloadType, it.rtxPayloadType)
                    },
                )
            }

            listeners.forEach { it.onMediaEngineConnectionConnected(this@RtcConnection) }
        }

        override fun onConnectionStateChange(
            connection: MediaEngineConnection,
            connectionState: MediaEngineConnection.ConnectionState,
        ) {
            val previous = connectionStateChange
            breadcrumb("Connection state change: $connectionState")
            setState(
                when (connectionState) {
                    MediaEngineConnection.ConnectionState.DISCONNECTED -> State.RtcDisconnected
                    MediaEngineConnection.ConnectionState.CONNECTING -> State.RtcConnecting
                    MediaEngineConnection.ConnectionState.CONNECTED -> State.RtcConnected
                    MediaEngineConnection.ConnectionState.NO_ROUTE -> State.NoRoute
                },
            )

            // Transport dropped while it was still coming up: the control socket is fine, so
            // re-running the handshake is cheaper than tearing the session down.
            if (previous.state == State.RtcConnecting && connectionStateChange.state == State.RtcDisconnected) {
                reconnect()
            }

            if (connectionStateChange.state == State.RtcConnected) {
                connectCompletedTime = clock.currentTimeMillis()
                hasConnected = true
                val properties = mutableMapOf<String, Any>("connect_count" to connectCount)
                val startedAt = connectStartTime
                val completedAt = connectCompletedTime
                if (startedAt != null && completedAt != null) {
                    properties["connect_time"] = completedAt - startedAt
                }
                addTransportProperties(properties)
                emitAnalyticsEvent(AnalyticsEvent.VOICE_CONNECTION_SUCCESS, properties)
            }
        }

        override fun onDestroy(connection: MediaEngineConnection) = Unit

        override fun onError(
            connection: MediaEngineConnection,
            exception: MediaEngineConnection.FailedConnectionException,
        ) {
            val reason = "connection error: ${exception.type}"
            if (exception.type.isBenign) {
                breadcrumb("$reason -- ${exception.message}")
            } else {
                val properties = mutableMapOf<String, Any>("connect_count" to connectCount)
                addTransportProperties(properties)
                emitAnalyticsEvent(AnalyticsEvent.VOICE_CONNECTION_FAILURE, properties)
            }
            handleFatalClose(true, reason, exception)
        }

        override fun onKrispStatus(connection: MediaEngineConnection, status: KrispOveruseDetector.Status) = Unit

        override fun onLocalMute(userId: Long, muted: Boolean) = Unit

        override fun onLocalVideoOffScreen(userId: Long, offScreen: Boolean) = Unit

        /** Our own voice activity; the server has to be told so other clients can render it. */
        override fun onSpeaking(userId: Long, ssrc: Int, speaking: Boolean) {
            if (userId == this@RtcConnection.userId) socket?.sendSpeaking(ssrc, speaking)
            listeners.forEach { it.onSpeaking(userId, speaking) }
        }

        override fun onTargetBitrate(bitrate: Int) = videoQuality.setTargetBitrate(bitrate)

        override fun onTargetFrameRate(frameRate: Int) = videoQuality.setTargetFrameRate(frameRate)

        override fun onVideo(
            userId: Long,
            streamId: Int?,
            audioSsrc: Int,
            videoSsrc: Int,
            rtxSsrc: Int,
            streams: Array<StreamParameters>,
        ) {
            if (userId == this@RtcConnection.userId) {
                val currentSocket = socket
                if (currentSocket == null) {
                    logError("sendVideo() socket was null.")
                } else {
                    this@RtcConnection.videoSsrc = videoSsrc.toLong()
                    if (videoSsrc != 0) sentVideo = true
                    currentSocket.sendVideo(
                        audioSsrc = audioSsrc,
                        videoSsrc = videoSsrc,
                        rtxSsrc = rtxSsrc,
                        streams = streams.map { it.toPayloadStream() },
                    )
                }
            }
            listeners.forEach { it.onVideoStream(userId, streamId, audioSsrc, videoSsrc, rtxSsrc) }
        }
    }

    /** Announces a peer to the engine and to the UI, with the resolution envelope when known. */
    private fun announceUserVideo(userId: Long, audioSsrc: Int, videoSsrc: Long, metadata: VideoMetadata?) {
        mediaEngineConnection?.connectUser(
            userId = userId,
            audioSsrc = audioSsrc,
            videoSsrc = videoSsrc.toInt(),
            isMuted = isUserMuted(userId),
            volume = userVolume(userId),
        )
        listeners.forEach { it.onUserCreated(this, userId) }
        if (metadata != null) listeners.forEach { it.onVideoMetadata(metadata) }
    }

    // endregion

    // region Analytics

    /**
     * Adds the properties every event carries.
     *
     * `context` distinguishes the voice connection from a Go Live one, and
     * `parent_media_session_id` is what ties a stream back to the call it was started from.
     */
    private fun emitAnalyticsEvent(event: AnalyticsEvent, properties: MutableMap<String, Any>) {
        properties["rtc_connection_id"] = id
        properties["context"] = when (rtcConnectionType) {
            is Type.Default -> "default"
            is Type.Stream -> "stream"
        }
        mediaSessionId?.let { properties["media_session_id"] = it }
        parentMediaSessionId?.let { properties["parent_media_session_id"] = it }
        listeners.forEach { it.onAnalyticsEvent(event, properties) }
    }

    private fun addTransportProperties(properties: MutableMap<String, Any>) {
        hostname?.let { properties["hostname"] = it }
        port?.let { properties["port"] = it }
    }

    /**
     * The end-of-session report.
     *
     * It is built from the *last* stats snapshot rather than a live query, because by the time this
     * runs the transport is already gone.
     */
    private fun reportVoiceDisconnect(willReconnect: Boolean, reason: String?) {
        val properties = mutableMapOf<String, Any>(
            "ping_bad_count" to pingBadCount,
            "connect_count" to connectCount,
            "channel_count" to 1,
        )
        addTransportProperties(properties)
        properties["reconnect"] = willReconnect
        reason?.let { properties["reason"] = it }

        if (pings.isNotEmpty()) {
            properties["ping_average"] = pings.average().roundToInt()
        }
        mediaSessionId?.let { properties["media_session_id"] = it }

        statsCollector?.let { collector ->
            collector.latestStats?.let { stats ->
                stats.outboundRtpAudio?.let { outbound ->
                    properties["packets_sent"] = outbound.packetsSent
                    properties["packets_sent_lost"] = outbound.packetsLost
                }
                properties["packets_received"] = stats.inboundRtpAudio.values.sumOf { it.packetsReceived }
                properties["packets_received_lost"] = stats.inboundRtpAudio.values.sumOf { it.packetsLost }
                collector.voiceQuality.run {
                    getDurationStats(properties)
                    getMosStats(properties)
                    getPacketStats(properties)
                    getBufferStats(properties)
                    getFrameOpStats(properties)
                }
            }
        }

        connectCompletedTime?.let { properties["duration"] = clock.currentTimeMillis() - it }
        transportInfo?.protocol?.let { properties["protocol"] = it.name.lowercase() }

        emitAnalyticsEvent(AnalyticsEvent.VOICE_DISCONNECT, properties)
    }

    /**
     * Emits one `VIDEO_STREAM_ENDED` per video stream that was live.
     *
     * A [Type.Stream] connection reports the one sender it was watching; a voice connection reports
     * every receiver it decoded. Either way the local outbound stream is reported too when this
     * client actually sent video.
     */
    private fun reportVideoStreamsEnded() {
        when (rtcConnectionType) {
            is Type.Stream -> {
                videoQuality.inboundReport(rtcConnectionType.senderId.toString())
                    ?.let { reportVideoStreamEnded(rtcConnectionType.senderId, it, RECEIVER) }
            }
            is Type.Default -> {
                videoQuality.inboundStreamKeys().forEach { streamKey ->
                    val senderId = streamKey.toLongOrNull() ?: return@forEach
                    videoQuality.inboundReport(streamKey)
                        ?.let { reportVideoStreamEnded(senderId, it, RECEIVER) }
                }
            }
        }
        if (sentVideo) {
            val participantType = if (rtcConnectionType is Type.Stream) STREAMER else SENDER
            reportVideoStreamEnded(userId, videoQuality.outboundReport(), participantType)
        }
    }

    private fun reportVideoStreamEnded(senderId: Long, report: Map<String, Any>, participantType: String) {
        val properties = mutableMapOf<String, Any>()
        guildId?.let { properties["guild_id"] = it }
        properties["channel_id"] = channelId
        properties["sender_user_id"] = senderId
        properties.putAll(report)
        properties["participant_type"] = participantType
        emitAnalyticsEvent(AnalyticsEvent.VIDEO_STREAM_ENDED, properties)
    }

    // endregion

    private fun setState(state: State) {
        if (connectionStateChange.state == state) return
        connectionStateChange = StateChange(state, getMetadata())
        listeners.forEach { it.onStateChange(connectionStateChange) }
    }

    private fun isUserMuted(userId: Long): Boolean = mutedUsers[userId] ?: false

    private fun userVolume(userId: Long): Float = userVolumes[userId] ?: DEFAULT_USER_VOLUME

    @AnyThread
    private fun runOnEngineThread(action: () -> Unit) {
        mediaEngine.mediaEngineThreadExecutor.run(action)
    }

    private fun breadcrumb(message: String) = AppLog.recordBreadcrumb(message, loggingTag)

    private fun logError(message: String, throwable: Throwable? = null) =
        AppLog.e(loggingTag, message, throwable)

    /** Server-declared layer → the engine's stream description. */
    private fun Payloads.Stream.toStreamParameters(): StreamParameters = StreamParameters(
        type = if (type == "video") MediaType.Video else MediaType.Audio,
        rid = rid.orEmpty(),
        ssrc = ssrc ?: 0,
        rtxSsrc = rtxSsrc ?: 0,
        active = active ?: false,
        maxBitrate = maxBitrate ?: 0,
        quality = quality ?: 100,
        maxPixelCount = 0,
    )

    /**
     * Engine layer → the server-facing description of what we will send.
     *
     * The resolution and frame-rate ceiling are the client's own capture budget rather than
     * anything the engine reports, because they are a promise to the server about the worst case.
     */
    private fun StreamParameters.toPayloadStream(): Payloads.Stream = Payloads.Stream(
        type = if (type == MediaType.Audio) "audio" else "video",
        rid = rid,
        maxFrameRate = VIDEO_CAPTURE_FRAME_RATE,
        quality = quality,
        ssrc = ssrc,
        rtxSsrc = rtxSsrc,
        maxResolution = Payloads.Stream.MaxResolution(
            Payloads.ResolutionType.Fixed,
            VIDEO_CAPTURE_WIDTH,
            VIDEO_CAPTURE_HEIGHT,
        ),
        active = active,
        maxBitrate = maxBitrate,
    )

    private companion object {
        private const val TAG = "RtcConnection"
        private val instanceCount = AtomicInteger()

        private const val SECURE_SCHEME = "wss"
        private const val INSECURE_SCHEME = "ws"

        private const val MEDIA_ENGINE_PREPARE_TIMEOUT_SECONDS = 2L
        private const val ONLINE_HEARTBEAT_DELAY_MS = 5_000L
        private const val OFFLINE_HEARTBEAT_DELAY_MS = 15_000L

        /** Past this, the voice server has certainly reaped the session. */
        private const val MAX_OFFLINE_RESUME_MS = 30_000L
        private const val WS_NORMAL_CLOSURE = 1000

        private const val MAX_PING_SAMPLES = 5
        private const val BAD_PING_MS = 500L
        private const val DEFAULT_USER_VOLUME = 1f

        private const val RECEIVER = "receiver"
        private const val SENDER = "sender"
        private const val STREAMER = "streamer"

        /** Local capture budget advertised to the voice server. */
        private const val VIDEO_CAPTURE_WIDTH = 1280
        private const val VIDEO_CAPTURE_HEIGHT = 720
        private const val VIDEO_CAPTURE_FRAME_RATE = 30
    }
}
