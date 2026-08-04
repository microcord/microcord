package com.discord.rtcconnection

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import co.discord.media_engine.RtcRegion
import co.discord.media_engine.VideoFrameCallback
import co.discord.media_engine.VideoInputDeviceDescription
import com.discord.api.channel.Channel
import com.discord.api.voice.server.VoiceServer
import com.discord.app.AppLog
import com.discord.models.domain.ModelRtcLatencyRegion
import com.discord.rtcconnection.audio.DiscordAudioManager
import com.discord.rtcconnection.mediaengine.EchoCancellationConfig
import com.discord.rtcconnection.mediaengine.MediaEngine
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.rtcconnection.mediaengine.MediaEngineLegacy
import com.discord.rtcconnection.mediaengine.MediaEngineThreadExecutor
import com.discord.rtcconnection.mediaengine.ThumbnailEmitter
import com.discord.stores.AudioOutputController
import com.discord.stores.Dispatcher
import com.discord.stores.StoreAudioManagerV2
import com.discord.stores.StoreMediaEngine
import com.discord.stores.StoreMediaSettings
import com.discord.stores.StoreRtcConnection
import com.discord.stores.StoreRtcRegion
import com.discord.stores.StoreStreamRtcConnection
import com.discord.stores.StoreUser
import com.discord.stores.StoreVideoStreams
import com.discord.utilities.networking.NetworkMonitor
import com.discord.utilities.time.Clock
import com.discord.widgets.voice.VoiceStoreAccess
import com.hammerandchisel.libdiscord.Discord
import javax.net.ssl.SSLSocketFactory
import rx.Subscription

/**
 * Composition root for voice: it owns the media engine and the audio router, builds the four
 * transport stores against them, and publishes the result to the UI.
 *
 * The stores and the transport are mutually dependent — a store issues commands to a connection, and
 * a connection reports state back into the store — so something has to close the loop. The stores
 * express their half as a controller interface with a no-op default; this class provides the real
 * implementations and is the only place that knows both sides exist.
 *
 * `StoreStream` installs this composition root after its REST and connectivity dependencies exist;
 * JVM store tests deliberately keep using the controller seams without constructing native media.
 *
 * Nothing here logs a token, an endpoint, or user content: [RtcConnection] already redacts those,
 * and [describe] exists so diagnostics have a safe surface to read instead.
 */
class VoiceTransport(
    context: Context,
    private val dispatcher: Dispatcher,
    private val clock: Clock,
    private val networkMonitor: NetworkMonitor,
    storeRtcRegion: StoreRtcRegion,
    private val storeUser: StoreUser,
    private val storeMediaSettings: StoreMediaSettings,
    private val storeVideoStreams: StoreVideoStreams,
    videoUseDetector: StoreAudioManagerV2.VideoUseDetector,
    /** `StoreChannels.findChannelByIdInternal`. */
    findChannel: (channelId: Long) -> Channel?,
    /** `StoreGatewayConnection.voiceStateUpdate` — opcode 4. */
    sendVoiceStateUpdate: (
        guildId: Long?,
        channelId: Long?,
        selfMute: Boolean,
        selfDeaf: Boolean,
        selfVideo: Boolean,
        preferredRegion: String?,
        shouldIncludePreferredRegion: Boolean,
    ) -> Unit,
    /** `StoreVoiceChannelSelected.getSelectedVoiceChannelId()`. */
    getSelectedVoiceChannelId: () -> Long = { 0L },
    private val openSLESConfig: MediaEngine.OpenSLESConfig = MediaEngine.OpenSLESConfig.DEFAULT,
    private val echoCancellation: EchoCancellationConfig = EchoCancellationConfig(),
) {

    private val appContext: Context = context.applicationContext

    private val engineExecutor = MediaEngineThreadExecutor.create()

    /**
     * Local mute and volume decisions, owned here rather than by a connection.
     *
     * A reconnect builds a fresh [RtcConnection], and a peer re-announced on the new one must come
     * back muted at the volume the user chose rather than briefly at full volume.
     */
    private val mutedUsers = LinkedHashMap<Long, Boolean>()
    private val userVolumes = LinkedHashMap<Long, Float>()

    private val mediaEngineListener = EngineListener()

    val mediaEngine: MediaEngine = MediaEngineLegacy(
        context = appContext,
        listener = mediaEngineListener,
        mediaEngineThreadExecutor = engineExecutor,
        openSLESConfig = openSLESConfig,
        echoCancellation = echoCancellation,
        echoCancellationCallback = { info -> AppLog.i(TAG, "echo cancellation: $info") },
    )

    private val voiceController = VoiceConnectionController()
    private val streamController = StreamConnectionControllerImpl()
    private val audioOutput = AudioOutputControllerImpl(appContext)

    val storeMediaEngine = StoreMediaEngine(MediaEngineControllerImpl())

    val storeRtcConnection = StoreRtcConnection(
        dispatcher = dispatcher,
        storeRtcRegion = storeRtcRegion,
        findChannel = findChannel,
        sendVoiceStateUpdate = sendVoiceStateUpdate,
        rtcConnectionController = voiceController,
    )

    val storeStreamRtcConnection = StoreStreamRtcConnection(
        dispatcher = dispatcher,
        userStore = storeUser,
        streamConnectionController = streamController,
    )

    val storeAudioManagerV2 = StoreAudioManagerV2(
        dispatcher = dispatcher,
        videoUseDetector = videoUseDetector,
        audioOutputController = audioOutput,
        getSelectedVoiceChannelId = getSelectedVoiceChannelId,
        updateStreamVolume = { volume -> storeStreamRtcConnection.updateStreamVolume(volume) },
    )

    private val meUserId: Long get() = storeUser.getMeInternal().id

    private var voiceSettingsSubscription: Subscription? = null

    @Volatile
    private var released = false

    init {
        storeMediaEngine.init()
        audioOutput.start()

        // The engine is the only consumer of these settings, so the subscription lives here rather
        // than in the settings store: nothing else needs to know a voice config exists.
        voiceSettingsSubscription = storeMediaSettings.getVoiceConfig().subscribe(
            { configuration ->
                mediaEngine.updateVoiceConfig(configuration.toMediaEngineVoiceConfig())
                dispatcher.schedule {
                    storeRtcConnection.handleSelfMuted(configuration.isSelfMuted())
                    storeRtcConnection.handleSelfDeafened(configuration.isSelfDeafened)
                }
            },
            { error -> AppLog.e(TAG, "failed to observe voice configuration", error) },
        )
        mediaEngine.setLocalVoiceStatusListener { status ->
            voiceController.onLocalVoiceStatus(status.amplitude, status.isSpeaking)
        }
    }

    /**
     * Starts or stops the Go Live screen capture.
     *
     * A `null` [permissionResultData] stops it. The capture runs on the *stream* connection, not the
     * voice one, which is why this is not simply a media-engine call: the frames have to go out over
     * the broadcast transport.
     *
     * The caller is responsible for holding a media-projection foreground service for the duration —
     * Android tears the projection down otherwise.
     */
    fun setScreenshare(permissionResultData: Intent?, thumbnailEmitter: ThumbnailEmitter? = null) =
        streamController.setScreenshare(permissionResultData, thumbnailEmitter)

    /** Publishes the three stores the voice UI reads through. Idempotent. */
    fun install() {
        VoiceStoreAccess.install(
            VoiceStoreAccess.Stores(
                mediaEngine = storeMediaEngine,
                audioManagerV2 = storeAudioManagerV2,
                rtcConnection = storeRtcConnection,
            ),
        )
    }

    /** Diagnostics snapshot. Contains no token, endpoint, or user content. */
    fun describe(): Map<String, Any?> = mapOf(
        "voiceConnection" to voiceController.describe(),
        "streamConnection" to streamController.describe(),
        "audioRouting" to audioOutput.describe(),
        "engineConnections" to mediaEngine.getConnections().size,
        "hasNativeEngine" to (mediaEngine.getNativeEngine() != null),
    )

    // region Media engine

    private inner class EngineListener : MediaEngine.Listener {
        override fun onConnected() = Unit
        override fun onConnecting() = Unit

        override fun onNativeEngineInitialized() {
            dispatcher.schedule { storeMediaEngine.handleNativeEngineInitialized() }
        }

        override fun onNewConnection(connection: MediaEngineConnection) {
            mediaEngine.updateVoiceConfig(storeMediaSettings.getVoiceConfigurationBlocking().toMediaEngineVoiceConfig())
            storeMediaEngine.handleNewConnection()
        }
    }

    private inner class MediaEngineControllerImpl : StoreMediaEngine.MediaEngineController {
        override fun getVideoInputDevices(callback: (List<VideoInputDeviceDescription>) -> Unit) =
            mediaEngine.getVideoInputDevices { devices -> callback(devices.toList()) }

        override fun setVideoInputDevice(index: Int) = mediaEngine.setVideoInputDevice(index)

        override fun setVideoInputDevice(deviceGuid: String) {
            mediaEngine.getNativeEngine()?.setVideoInputDevice(deviceGuid)
        }

        override fun setVideoOutputSink(streamId: String, callback: VideoFrameCallback?) {
            mediaEngine.getNativeEngine()?.setVideoOutputSink(
                streamId,
                callback?.let { sink -> Discord.VideoFrameCallback(sink::onFrame) },
            )
        }

        /**
         * There is no engine-level "video on" switch; broadcasting is a per-connection decision, so
         * this fans out to every live connection.
         */
        override fun setVideoInputEnabled(enabled: Boolean) {
            voiceController.setVideoEnabled(enabled)
            dispatcher.schedule { storeRtcConnection.handleSelfVideo(enabled) }
        }

        override fun setAudioInputEnabled(enabled: Boolean) = mediaEngine.setAudioInputEnabled(enabled)

        override fun setPttActive(active: Boolean) =
            mediaEngine.getConnections().forEach { it.setPttActive(active) }

        override fun rankRtcRegions(
            regions: List<ModelRtcLatencyRegion>,
            callback: (List<String>) -> Unit,
        ) {
            val probes = regions.map { RtcRegion(it.region, it.ips.toTypedArray()) }.toTypedArray()
            mediaEngine.rankRtcRegions(probes) { ranked -> callback(ranked.toList()) }
        }
    }

    // endregion

    // region Voice connection

    /**
     * Builds and tears down the voice [RtcConnection].
     *
     * A `VOICE_SERVER_UPDATE` for the channel we are already on is not a reason to rebuild: the
     * gateway re-sends it after a resume, and replacing a healthy connection would drop the call.
     */
    private inner class VoiceConnectionController : StoreRtcConnection.RtcConnectionController {

        private var connection: RtcConnection? = null
        private var connectedChannelId: Long? = null
        private var connectedSessionId: String? = null

        override fun connect(
            voiceServer: VoiceServer,
            channelId: Long,
            guildId: Long?,
            sessionId: String,
        ) {
            if (released) return
            val existing = connection
            if (existing != null && connectedChannelId == channelId && connectedSessionId == sessionId) {
                // Same session, new endpoint: reuse the connection so mute state and peer volumes
                // survive a server move.
                existing.sessionId = sessionId
                existing.connect(voiceServer.endpoint, voiceServer.token, sslSocketFactory())
                return
            }

            disconnect("Rebuilding for channel $channelId")
            val created = RtcConnection(
                guildId = guildId,
                channelId = channelId,
                sessionId = sessionId,
                isVideoEnabled = true,
                rtcServerId = (guildId ?: channelId).toString(),
                userId = meUserId,
                mediaEngine = mediaEngine,
                clock = clock,
                rtcConnectionType = RtcConnection.Type.Default,
                networkMonitor = networkMonitor,
                mutedUsers = mutedUsers,
                userVolumes = userVolumes,
                loggingTagPrefix = "Voice",
            )
            created.addListener(VoiceConnectionListener())
            connection = created
            connectedChannelId = channelId
            connectedSessionId = sessionId
            created.connect(voiceServer.endpoint, voiceServer.token, sslSocketFactory())
        }

        override fun disconnect(reason: String) {
            val existing = connection ?: return
            AppLog.recordBreadcrumb("Disconnecting voice connection: $reason", TAG)
            existing.disconnect()
            connection = null
            connectedChannelId = null
            connectedSessionId = null
        }

        override val metadata: RtcConnection.Metadata? get() = connection?.getMetadata()

        override fun setUserMuted(userId: Long, muted: Boolean) {
            mutedUsers[userId] = muted
            connection?.setUserMuted(userId, muted)
        }

        override fun setUserOutputVolume(userId: Long, volume: Float) {
            userVolumes[userId] = volume
            connection?.setUserOutputVolume(userId, volume)
        }

        override fun setUserOffScreen(userId: Long, offScreen: Boolean) {
            connection?.setUserOffScreen(userId, offScreen)
        }

        override fun setFocusedParticipant(userId: Long?) {
            connection?.setFocusedParticipant(userId)
        }

        override fun setSelfMuted(muted: Boolean) {
            connection?.setSelfMuted(muted)
        }

        override fun setSelfDeafened(deafened: Boolean) {
            connection?.setSelfDeafened(deafened)
        }

        override fun setSelfVideo(enabled: Boolean) {
            connection?.setSelfVideo(enabled)
        }

        fun onLocalVoiceStatus(amplitude: Float, speaking: Boolean) {
            connection?.onLocalVoiceStatus(amplitude, speaking)
        }

        fun setVideoEnabled(enabled: Boolean) {
            connection?.setSelfVideo(enabled)
        }

        fun describe(): Map<String, Any?> = connection?.describe() ?: emptyMap()
    }

    /**
     * Forwards one connection's state into the stores.
     *
     * `RtcConnected` is the only state that counts as "connected" for the stores: the control socket
     * being up says nothing about whether audio is flowing.
     */
    private inner class VoiceConnectionListener : RtcConnection.DefaultListener() {

        override fun onStateChange(stateChange: RtcConnection.StateChange) {
            val connected = stateChange.state == RtcConnection.State.RtcConnected
            storeRtcConnection.onConnectedChanged(connected)
            dispatcher.schedule { storeAudioManagerV2.handleRtcConnectionState(connected) }
        }

        override fun onSpeaking(userId: Long, speaking: Boolean) =
            storeRtcConnection.onSpeaking(userId, speaking)

        override fun onMediaSessionIdReceived() = storeRtcConnection.onMediaSessionIdReceived()

        override fun onVideoStream(userId: Long, streamId: Int?, audioSsrc: Int, videoSsrc: Int, rtxSsrc: Int) {
            dispatcher.schedule { storeVideoStreams.handleVideoStreamUpdate(userId, streamId) }
        }

        override fun onVideoMetadata(metadata: VideoMetadata) {
            dispatcher.schedule { storeVideoStreams.handleVideoMetadataUpdate(metadata) }
        }
    }

    // endregion

    // region Stream connection

    /**
     * The second connection, the one carrying a Go Live broadcast.
     *
     * It is created by `STREAM_CREATE` but cannot dial until `STREAM_SERVER_UPDATE` names an
     * endpoint, so [connect] deliberately builds it in [RtcConnection.State.AwaitingEndpoint] by
     * passing a null endpoint, and [updateServer] is what actually opens it.
     */
    private inner class StreamConnectionControllerImpl :
        StoreStreamRtcConnection.StreamConnectionController {

        private var connection: RtcConnection? = null
        private var channelId: Long? = null
        private var sessionId: String? = null
        private var screenshareIntent: Intent? = null
        private var thumbnailEmitter: ThumbnailEmitter? = null

        override val connectedChannelId: Long? get() = channelId

        override val connectedSessionId: String? get() = sessionId

        override fun connect(
            userId: Long,
            guildId: Long?,
            channelId: Long,
            sessionId: String,
            rtcServerId: String,
            streamOwnerId: Long,
            streamKey: String,
        ) {
            if (released) return
            disconnect()
            val created = RtcConnection(
                guildId = guildId,
                channelId = channelId,
                sessionId = sessionId,
                isVideoEnabled = true,
                rtcServerId = rtcServerId,
                userId = userId,
                mediaEngine = mediaEngine,
                clock = clock,
                rtcConnectionType = RtcConnection.Type.Stream(streamOwnerId),
                networkMonitor = networkMonitor,
                parentMediaSessionId = voiceController.metadata?.mediaSessionId,
                loggingTagPrefix = "Stream",
                streamKey = streamKey,
            )
            created.addListener(StreamConnectionListener())
            connection = created
            this.channelId = channelId
            this.sessionId = sessionId
            created.connect(null, null, sslSocketFactory())
        }

        override fun updateServer(endpoint: String?, token: String?) {
            connection?.connect(endpoint, token, sslSocketFactory())
        }

        override fun disconnect() {
            connection?.disconnect()
            connection = null
            channelId = null
            sessionId = null
        }

        override fun setUserOutputVolume(userId: Long, volume: Float) {
            connection?.setUserOutputVolume(userId, volume)
        }

        override fun setFocusedParticipant(userId: Long?) {
            connection?.setFocusedParticipant(userId)
        }

        fun setScreenshare(permissionResultData: Intent?, thumbnailEmitter: ThumbnailEmitter?) {
            screenshareIntent = permissionResultData
            this.thumbnailEmitter = thumbnailEmitter
            if (permissionResultData == null) connection?.setScreenshare(null)
        }

        fun startScreenshare() {
            screenshareIntent?.let { connection?.setScreenshare(it, thumbnailEmitter) }
        }

        fun describe(): Map<String, Any?> = connection?.describe() ?: emptyMap()
    }

    private inner class StreamConnectionListener : RtcConnection.DefaultListener() {
        override fun onStateChange(stateChange: RtcConnection.StateChange) {
            val connected = stateChange.state == RtcConnection.State.RtcConnected
            dispatcher.schedule {
                storeStreamRtcConnection.handleStreamRtcConnectionStateChange(connected)
                storeStreamRtcConnection.handleMediaSessionIdReceived(stateChange.metadata?.mediaSessionId)
            }
            if (connected) streamController.startScreenshare()
        }

        override fun onQualityUpdate(quality: RtcConnection.Quality) {
            dispatcher.schedule { storeStreamRtcConnection.handleQualityUpdate(quality) }
        }

        override fun onVideoStream(userId: Long, streamId: Int?, audioSsrc: Int, videoSsrc: Int, rtxSsrc: Int) {
            dispatcher.schedule { storeVideoStreams.handleApplicationStreamUpdate(userId, streamId) }
        }

        override fun onVideoMetadata(metadata: VideoMetadata) {
            dispatcher.schedule { storeVideoStreams.handleApplicationStreamVideoMetadataUpdate(metadata) }
        }
    }

    // endregion

    // region Audio routing

    /**
     * Runs [DiscordAudioManager] on its own thread and mirrors it into [StoreAudioManagerV2].
     *
     * The router asserts that every mutating call comes from the thread that constructed it, so the
     * instance is created on this thread and every command is posted to it. Device changes come back
     * on Rx subjects and are scheduled onto the dispatcher, because the store fields they write are
     * store-thread state.
     */
    private inner class AudioOutputControllerImpl(private val context: Context) : AudioOutputController {

        private val thread = HandlerThread("AudioManagerThread").apply { start() }
        private val handler = Handler(thread.looper)

        @Volatile
        private var router: DiscordAudioManager? = null

        private val subscriptions = mutableListOf<Subscription>()

        fun start() = handler.post {
            val created = DiscordAudioManager(context)
            router = created
            subscriptions += created.observeAudioDevices().subscribe(
                { devices -> dispatcher.schedule { storeAudioManagerV2.updateAudioDevices(devices) } },
                { error -> AppLog.e(TAG, "failed to observe audio devices", error) },
            )
            subscriptions += created.observeActiveAudioDevice().subscribe(
                { device -> dispatcher.schedule { storeAudioManagerV2.updateActiveAudioDevice(device) } },
                { error -> AppLog.e(TAG, "failed to observe active audio device", error) },
            )
            subscriptions += created.observeMediaVolume().subscribe(
                { volume -> dispatcher.schedule { storeAudioManagerV2.updateCurrentMediaVolume(volume) } },
                { error -> AppLog.e(TAG, "failed to observe media volume", error) },
            )
        }

        override fun selectOutputDevice(device: DiscordAudioManager.DeviceTypes) {
            handler.post { router?.setDevice(device) }
        }

        override fun setCallActive(active: Boolean) {
            handler.post { router?.setCallActive(active) }
        }

        override fun setMediaVolume(ratio: Float) {
            handler.post { router?.setMediaVolume(ratio) }
        }

        /**
         * The store passes "is my camera on"; the router's answer to that is to *remove* the
         * earpiece, because nobody holds a phone to their ear to watch video.
         *
         * The seam is misnamed — see the handoff note — so the mapping is spelled out here rather
         * than hidden behind a matching parameter name.
         */
        override fun setEarpieceForceAvailable(forceAvailable: Boolean) {
            handler.post { router?.setVideoActive(forceAvailable) }
        }

        override val maxMediaVolume: Int get() = router?.maxMediaVolume ?: 0

        fun describe(): Map<String, Any?> = router?.describe() ?: emptyMap()

        fun release() {
            handler.post {
                subscriptions.forEach { it.unsubscribe() }
                subscriptions.clear()
                try {
                    router?.release()
                } finally {
                    router = null
                    thread.quitSafely()
                }
            }
        }
    }

    // endregion

    private fun sslSocketFactory(): SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory

    /** One-way process teardown. The singleton remains retained to prevent a second native engine. */
    @Synchronized
    fun release() {
        if (released) return
        released = true

        streamController.disconnect()
        voiceController.disconnect("Voice transport released")
        voiceSettingsSubscription?.unsubscribe()
        voiceSettingsSubscription = null
        audioOutput.release()
    }

    companion object {
        private const val TAG = "VoiceTransport"

        @Volatile
        private var instance: VoiceTransport? = null

        /**
         * Builds the transport once and publishes it to the voice UI.
         *
         * Safe to call more than once; the second call returns the existing instance rather than
         * creating a second media engine, which would fight the first one for the microphone.
         */
        @JvmStatic
        @Synchronized
        fun install(build: () -> VoiceTransport): VoiceTransport =
            instance ?: build().also {
                instance = it
                it.install()
            }

        @JvmStatic
        fun getOrNull(): VoiceTransport? = instance
    }
}

/**
 * Settings → engine.
 *
 * Two mappings are not one-to-one and are the reason this exists rather than the engine reading
 * [StoreMediaSettings.VoiceConfiguration] directly:
 * - `NoiseProcessing` is a single user choice that the engine exposes as two independent switches,
 *   and `CancellationTemporarilyDisabled` (Krisp backed off under CPU pressure) has to turn
 *   cancellation off without changing what the user chose.
 * - `isSelfMuted()` is the *implied* value: deafened forces muted, and the engine must be told the
 *   implied one or a deafened user keeps transmitting.
 *
 * The reference declares this as `VoiceConfiguration.toMediaEngineVoiceConfig()` on the store's own
 * type; it lives here because `stores/` is owned elsewhere. Move it when that file is next touched.
 */
internal fun StoreMediaSettings.VoiceConfiguration.toMediaEngineVoiceConfig(): MediaEngine.VoiceConfig =
    MediaEngine.VoiceConfig(
        outputVolume = outputVolume,
        echoCancellation = echoCancellation,
        // PR 752 disables Krisp because its Play Asset Delivery model is unavailable in a rebuilt app.
        noiseSuppression = noiseProcessing != StoreMediaSettings.NoiseProcessing.None,
        noiseCancellation = false,
        automaticGainControl = automaticGainControl,
        inputMode = inputMode,
        inputModeOptions = MediaEngineConnection.InputModeOptions(
            vadThreshold = sensitivity.toInt(),
            vadAutoThreshold = automaticVad,
            vadUseKrisp = false,
        ),
        isSelfDeafened = isSelfDeafened,
        isSelfMuted = isSelfMuted(),
    )
