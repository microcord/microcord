package com.discord.rtcconnection.mediaengine

import android.content.Intent
import android.media.projection.MediaProjection
import co.discord.media_engine.AudioDecoder
import co.discord.media_engine.AudioEncoder
import co.discord.media_engine.Connection
import co.discord.media_engine.EncryptionSettings
import co.discord.media_engine.Stats
import co.discord.media_engine.StreamParameters
import co.discord.media_engine.VideoDecoder
import co.discord.media_engine.VideoEncoder
import com.discord.app.AppLog
import com.discord.rtcconnection.Codec
import com.discord.rtcconnection.KrispOveruseDetector
import com.discord.rtcconnection.mediaengine.MediaEngineConnection.FailedConnectionException.FailureType
import com.hammerandchisel.libdiscord.Discord
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [MediaEngineConnection] backed by `libdiscord.so`.
 *
 * Construction *is* the connect: `Discord.connectToServer` returns a native `Connection` handle
 * immediately and reports the outcome later through [Discord.ConnectToServerCallback]. Every
 * subsequent call is forwarded to that handle under [connectionLock], because the engine's own
 * callback threads and the engine executor both reach in here.
 *
 * Reference: `b.a.q.m0.c.e` (`MediaEngineConnectionLegacy`).
 */
internal class MediaEngineConnectionLegacy(
    private val executor: MediaEngineThreadExecutor,
    private val noiseCancellationConfig: NoiseCancellationConfig,
    nativeEngine: Discord,
    /** Video codecs this device advertised, in the order they were ranked. */
    private val supportedVideoCodecs: List<Codec>,
    override val type: MediaEngineConnection.Type,
    private val userId: Long,
    connectionOptions: MediaEngine.ConnectionOptions,
    listeners: List<MediaEngineConnection.Listener>,
) : MediaEngineConnection {

    private val connectionLock = Any()
    private val listeners = CopyOnWriteArrayList(listeners)

    private var connectionState: MediaEngineConnection.ConnectionState =
        MediaEngineConnection.ConnectionState.CONNECTING

    private var selfMute: Boolean = false

    /** Last audio ssrc announced per user; the key set is also "who is in this session". */
    private val audioSsrcs: MutableMap<Long, Int> = mutableMapOf(userId to connectionOptions.ssrc)

    /** Last video ssrc seen per user, from either the control socket or the engine's own callback. */
    private val videoSsrcs: MutableMap<Long, Long> = mutableMapOf()

    private val mutedUsers: MutableSet<Long> = linkedSetOf()
    private val videoDisabledUsers: MutableSet<Long> = linkedSetOf()
    private val offScreenUsers: MutableSet<Long> = linkedSetOf()

    /** Codecs actually in play: `opus` plus whatever video codecs this device offered. */
    private val negotiatedCodecs: MutableList<Codec> = mutableListOf()

    override var encryptionModes: List<String> = emptyList()
        private set

    private val connection: Connection = nativeEngine.run {
        Discord.nextConnectionIsStream = type == MediaEngineConnection.Type.STREAM
        connectToServer(
            connectionOptions.ssrc,
            userId,
            connectionOptions.ip,
            connectionOptions.port,
            connectionOptions.streams.toTypedArray(),
            { connectionInfo, errorMessage -> onEngineThread { handleConnection(connectionInfo, errorMessage) } },
        )
    }.apply {
        setOnVideoCallback { videoUserId, ssrc, streamIdentifier, streams ->
            onEngineThread { handleVideo(videoUserId, ssrc, streamIdentifier, streams) }
        }
        setUserSpeakingStatusChangedCallback { speakingUserId, isSpeakingNow, _ ->
            onEngineThread { handleSpeaking(speakingUserId, isSpeakingNow) }
        }
    }

    init {
        notifyListeners { it.onConnectionStateChange(this, connectionState) }
    }

    // region Native callbacks

    /**
     * Outcome of `connectToServer`.
     *
     * The engine reports failures as a free-text message rather than a code, so the message is
     * matched against the three phrases the native side emits for expected races; anything else is
     * `UNKNOWN` and will be treated as a real failure by the listener.
     */
    private fun handleConnection(connectionInfo: Discord.ConnectionInfo?, errorMessage: String?) {
        AppLog.i(TAG, "handleConnection(). errorMessage: $errorMessage")

        if (connectionInfo != null && errorMessage.isNullOrEmpty()) {
            val transportInfo = MediaEngineConnection.TransportInfo(
                address = connectionInfo.localAddress,
                port = connectionInfo.localPort,
                protocol = MediaEngineConnection.TransportInfo.Protocol.valueOf(
                    connectionInfo.protocol.uppercase(),
                ),
            )
            connectionState = MediaEngineConnection.ConnectionState.CONNECTED
            notifyListeners { it.onConnectionStateChange(this, connectionState) }

            negotiatedCodecs.clear()
            negotiatedCodecs += Codec(AUDIO_CODEC_NAME, 1, "audio", AUDIO_PAYLOAD_TYPE, null)
            negotiatedCodecs += supportedVideoCodecs

            withConnection {
                it.getEncryptionModes { modes ->
                    onEngineThread {
                        encryptionModes = modes.orEmpty().filterNotNull()
                        AppLog.i(TAG, "Encryption modes: $encryptionModes")
                        notifyListeners { listener -> listener.onConnected(this, transportInfo, negotiatedCodecs) }
                    }
                }
            }
            return
        }

        if (connectionInfo == null) {
            val message = "No connection info. Error message from media engine: $errorMessage"
            notifyError(message, FailureType.NO_CONNECTION_INFO)
        } else {
            notifyError(errorMessage, classifyFailure(errorMessage))
        }
    }

    private fun classifyFailure(errorMessage: String?): FailureType = when {
        errorMessage == null -> FailureType.UNKNOWN
        errorMessage.contains("Disconnected before we managed to connect", ignoreCase = true) ->
            FailureType.DISCONNECTED_BEFORE_CONNECTION_ESTABLISHED
        errorMessage.contains("OnConnectAttemptTimedOut", ignoreCase = true) -> FailureType.TIMEOUT
        errorMessage.contains("OnAddressIPResolved", ignoreCase = true) -> FailureType.ADDRESS_IP_RESOLVED
        else -> FailureType.UNKNOWN
    }

    /**
     * The engine discovered a peer's video ssrc.
     *
     * `streamIdentifier` is the Go Live stream key as a bare integer when this is a broadcast, and
     * unparseable otherwise; the reference passes `toIntOrNull()` straight through.
     */
    private fun handleVideo(
        videoUserId: Long,
        videoSsrc: Int,
        streamIdentifier: String,
        streams: Array<StreamParameters>,
    ) {
        videoSsrcs[videoUserId] = videoSsrc.toLong()
        notifyListeners {
            it.onVideo(
                userId = videoUserId,
                streamId = streamIdentifier.toIntOrNull(),
                audioSsrc = audioSsrcs[videoUserId] ?: 0,
                videoSsrc = videoSsrc,
                rtxSsrc = rtxSsrcFor(videoSsrc),
                streams = streams,
            )
        }
    }

    private fun handleSpeaking(speakingUserId: Long, isSpeaking: Boolean) {
        val audioSsrc = audioSsrcs[speakingUserId] ?: 0
        notifyListeners { it.onSpeaking(speakingUserId, audioSsrc, isSpeaking) }
    }

    // endregion

    // region Peers

    override fun connectUser(userId: Long, audioSsrc: Int, videoSsrc: Int?, isMuted: Boolean, volume: Float) {
        if (audioSsrc == 0) {
            AppLog.w(TAG, "Attempting to create user $userId with 0 audio SSRC")
            return
        }

        val resolvedVideoSsrc = videoSsrc?.toLong() ?: videoSsrcs[userId] ?: 0L
        val audioChanged = audioSsrcs[userId] != audioSsrc
        val videoChanged = videoSsrcs[userId] != resolvedVideoSsrc

        // Re-announcing an unchanged peer would tear down and rebuild its decoder for nothing; the
        // control socket repeats these on every speaking edge.
        if (audioChanged || videoChanged) {
            AppLog.i(TAG, "creating user: $userId with audio SSRC: $audioSsrc and video SSRC: $videoSsrc")
            withConnection {
                it.connectUser(
                    userId,
                    audioSsrc,
                    resolvedVideoSsrc.toInt(),
                    rtxSsrcFor(resolvedVideoSsrc.toInt()),
                    isMuted,
                    volume,
                )
            }
        }

        if (isMuted) mutedUsers += userId else mutedUsers -= userId
        audioSsrcs[userId] = audioSsrc
        videoSsrcs[userId] = resolvedVideoSsrc
    }

    override fun setUserMuted(userId: Long, mute: Boolean) {
        withConnection { it.muteUser(userId, mute) }
        if (mute) mutedUsers += userId else mutedUsers -= userId
        notifyListeners { it.onLocalMute(userId, mute) }
    }

    override fun isUserMuted(userId: Long): Boolean = userId in mutedUsers

    override fun setUserPlayoutVolume(userId: Long, volume: Float) =
        withConnection { it.setUserPlayoutVolume(userId, volume / PERCENT) }

    override fun setUserVideoDisabled(userId: Long, disabled: Boolean) {
        if (disabled) videoDisabledUsers += userId else videoDisabledUsers -= userId
    }

    override fun isUserVideoDisabled(userId: Long): Boolean = userId in videoDisabledUsers

    override fun setUserOffScreen(userId: Long, offScreen: Boolean) {
        if (offScreen) offScreenUsers += userId else offScreenUsers -= userId
        notifyListeners { it.onLocalVideoOffScreen(userId, offScreen) }
    }

    override fun isUserOffScreen(userId: Long): Boolean = userId in offScreenUsers

    // endregion

    // region Local state

    override fun setSelfMute(selfMute: Boolean) {
        this.selfMute = selfMute
        withConnection { it.muteLocalUser(selfMute) }
    }

    override fun setSelfDeafen(selfDeaf: Boolean) = withConnection { it.deafenLocalUser(selfDeaf) }

    override fun setPttActive(active: Boolean) = withConnection { it.setPTTActive(active) }

    override fun setInputMode(
        inputMode: MediaEngineConnection.InputMode,
        inputModeOptions: MediaEngineConnection.InputModeOptions,
    ) = withConnection {
        it.setVADLeadingFramesToBuffer(inputModeOptions.vadLeadingFrames)
        it.setVADTrailingFramesToSend(inputModeOptions.vadTrailingFrames)
        it.setVADTriggerThreshold(inputModeOptions.vadThreshold.toFloat())
        // The engine takes a sensitivity level, not a flag: 3 is "adapt", -1 is "use my threshold".
        it.setVADAutoThreshold(if (inputModeOptions.vadAutoThreshold) VAD_AUTO_THRESHOLD else VAD_FIXED_THRESHOLD)
        it.setVADUseKrisp(inputModeOptions.vadUseKrisp)
        it.setAudioInputMode(inputMode.numeral)
        it.setExpectedPacketLossRate(EXPECTED_PACKET_LOSS_RATE)
    }

    override fun setVideoBroadcast(isVideoBroadcast: Boolean) =
        withConnection { it.setVideoBroadcast(isVideoBroadcast) }

    override fun setEncodingQuality(quality: MediaEngineConnection.EncodingQuality) {
        // While self-muted the encoder is dropped to the lower "muted" frame rate: nobody is
        // talking over this video, so latency matters less than uplink.
        val frameRate = if (selfMute) quality.mutedFramerate else quality.framerate
        notifyListeners { it.onTargetFrameRate(frameRate) }
        notifyListeners { it.onTargetBitrate(quality.maxBitrate) }
        withConnection {
            it.setEncodingQuality(quality.minBitrate, quality.maxBitrate, quality.width, quality.height, frameRate)
        }
    }

    override fun isNoiseCancellationEnabled(): Boolean = noiseCancellationConfig.enabled

    // endregion

    // region Screen share

    override fun startScreenshare(permission: Intent, thumbnailEmitter: ThumbnailEmitter?) =
        synchronized(connectionLock) {
            val capturer = ScreenCapturer(
                mediaProjectionPermissionResultData = permission,
                mediaProjectionCallback = object : MediaProjection.Callback() {
                    override fun onStop() = Unit
                },
                thumbnailEmitter = thumbnailEmitter,
            )
            connection.startScreenshareBroadcast(capturer, capturer.soundshareAudioSource.nativeInstance)
        }

    override fun stopScreenshare() = synchronized(connectionLock) { connection.stopScreenshareBroadcast() }

    // endregion

    // region Transport negotiation

    override fun setEncryptionSettings(mode: String, secretKey: IntArray) =
        withConnection { it.setEncryptionSettings(EncryptionSettings(mode, secretKey)) }

    override fun selectCodecs(audioCodec: String, videoCodec: String) {
        val audio = negotiatedCodecs.firstOrNull { it.name == audioCodec }
            ?: return notifyError("Missing audio codec: $audioCodec.", FailureType.CODEC_NEGOTIATION_FAILED)
        val video = negotiatedCodecs.firstOrNull { it.name == videoCodec }
            ?: return notifyError("Missing video codec: $videoCodec.", FailureType.CODEC_NEGOTIATION_FAILED)
        val videoRtxPayloadType = video.rtxPayloadType
            ?: return notifyError(
                "Video codec $videoCodec rtxPayloadType was null.",
                FailureType.CODEC_NEGOTIATION_FAILED,
            )

        val audioEncoder = AudioEncoder(
            type = audio.payloadType,
            name = audioCodec,
            freq = OPUS_CLOCK_RATE,
            pacsize = OPUS_FRAME_SAMPLES,
            channels = 1,
            rate = OPUS_BITRATE,
        )
        val audioDecoder = AudioDecoder(
            type = audio.payloadType,
            name = audioCodec,
            freq = OPUS_CLOCK_RATE,
            channels = 2,
            params = mapOf("stereo" to "1"),
        )
        val videoParams = mapOf(
            "level-asymmetry-allowed" to "1",
            "packetization-mode" to "1",
            "profile-level-id" to H264_PROFILE_LEVEL_ID,
        )
        val videoEncoder = VideoEncoder(videoCodec, video.payloadType, videoRtxPayloadType, videoParams)
        val videoDecoders = negotiatedCodecs.filter { it.type == "video" }.mapNotNull { codec ->
            codec.rtxPayloadType?.let { VideoDecoder(codec.name, codec.payloadType, it, videoParams.toMutableMap()) }
        }.toTypedArray()

        withConnection {
            it.setCodecs(audioEncoder, videoEncoder, arrayOf(audioDecoder), videoDecoders)
        }
    }

    // endregion

    // region DAVE

    override fun connectUsers(userIds: List<String>) = withConnection { it.connectUsers(userIds) }
    override fun destroyUser(userId: String) = withConnection { it.destroyUser(userId) }
    override fun prepareSecureFramesTransition(
        transitionId: Int,
        protocolVersion: Int,
        callback: () -> Unit,
    ) = withConnection { it.prepareSecureFramesTransition(transitionId, protocolVersion) { callback() } }
    override fun executeSecureFramesTransition(transitionId: Int) =
        withConnection { it.executeSecureFramesTransition(transitionId) }
    override fun prepareSecureFramesEpoch(epoch: String, transitionId: Int, groupId: String) =
        withConnection { it.prepareSecureFramesEpoch(epoch, transitionId, groupId) }
    override fun getMLSKeyPackageB64(callback: (String) -> Unit) =
        withConnection { it.getMLSKeyPackageB64(callback) }
    override fun updateMLSExternalSenderB64(externalSender: String) =
        withConnection { it.updateMLSExternalSenderB64(externalSender) }
    override fun processMLSProposalsB64(proposals: String, callback: (String) -> Unit) =
        withConnection { it.processMLSProposalsB64(proposals, callback) }
    override fun prepareMLSCommitTransitionB64(
        transitionId: Int,
        commit: String,
        callback: (Boolean, Int, String) -> Unit,
    ) = withConnection { it.prepareMLSCommitTransitionB64(transitionId, commit, callback) }
    override fun processMLSWelcomeB64(
        transitionId: Int,
        welcome: String,
        callback: (Boolean, Int, String) -> Unit,
    ) = withConnection { it.processMLSWelcomeB64(transitionId, welcome, callback) }
    override fun setOnMLSFailureCallback(callback: (String, String) -> Unit) =
        withConnection { it.setOnMLSFailureCallback(callback) }
    override fun setSecureFramesStateUpdateCallback(callback: (String) -> Unit) =
        withConnection { it.setSecureFramesStateUpdateCallback(callback) }

    // endregion

    override fun addListener(listener: MediaEngineConnection.Listener) {
        listeners += listener
        // Replayed immediately so a listener attached after CONNECTED does not wait for the next
        // transition that may never come.
        listener.onConnectionStateChange(this, connectionState)
    }

    override fun removeListener(listener: MediaEngineConnection.Listener) {
        listeners -= listener
    }

    override fun getStats(onStats: (Stats) -> Unit) {
        val sink = onStats
        withConnection {
            it.getStats(object : Connection.GetStatsCallback {
                override fun onStats(stats: Stats) = sink(stats)
                override fun onStatsError(t: Throwable?) =
                    AppLog.e(TAG, "Failed to parse stats from the media engine", t)
            })
        }
    }

    override fun notifyKrispStatus(status: KrispOveruseDetector.Status) =
        notifyListeners { it.onKrispStatus(this, status) }

    override fun destroy() {
        AppLog.i(TAG, "destroy(). Disconnecting from server")
        withConnection { it.dispose() }
        notifyListeners {
            it.onConnectionStateChange(this, MediaEngineConnection.ConnectionState.DISCONNECTED)
            it.onDestroy(this)
        }
    }

    private fun notifyError(message: String?, type: FailureType) =
        notifyListeners { it.onError(this, MediaEngineConnection.FailedConnectionException(message, type)) }

    /**
     * A listener that throws must not stop the others from being told; a video tile blowing up
     * cannot be allowed to strand the connection state machine.
     */
    private inline fun notifyListeners(block: (MediaEngineConnection.Listener) -> Unit) {
        listeners.forEach { listener ->
            try {
                block(listener)
            } catch (e: Exception) {
                AppLog.e(TAG, "Error in listener", e)
            }
        }
    }

    private inline fun withConnection(block: (Connection) -> Unit) =
        synchronized(connectionLock) { block(connection) }

    private fun onEngineThread(action: () -> Unit) {
        executor.run(action)
    }

    /** The engine's convention: retransmission ssrc is video ssrc + 1, or 0 when there is no video. */
    private fun rtxSsrcFor(videoSsrc: Int): Int = if (videoSsrc > 0) videoSsrc + 1 else 0

    private companion object {
        private const val TAG = "MediaEngineConnectionLegacy"
        private const val AUDIO_CODEC_NAME = "opus"
        private const val AUDIO_PAYLOAD_TYPE = 120
        private const val OPUS_CLOCK_RATE = 48_000
        private const val OPUS_FRAME_SAMPLES = 960
        private const val OPUS_BITRATE = 64_000
        private const val H264_PROFILE_LEVEL_ID = "42e01f"
        private const val EXPECTED_PACKET_LOSS_RATE = 0.3f
        private const val VAD_AUTO_THRESHOLD = 3
        private const val VAD_FIXED_THRESHOLD = -1
        private const val PERCENT = 100f
    }
}

/**
 * Shared "is Krisp on" flag.
 *
 * The engine sets it from the voice config and every connection reads it, so it lives outside both.
 * Reference: `b.a.q.m0.c.u`.
 */
internal class NoiseCancellationConfig {
    @Volatile
    var enabled: Boolean = false
}
