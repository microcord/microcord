package com.discord.rtcconnection.mediaengine

import android.content.Intent
import co.discord.media_engine.Stats
import co.discord.media_engine.StreamParameters
import com.discord.rtcconnection.Codec
import com.discord.rtcconnection.KrispOveruseDetector

/**
 * One live media session inside the native engine: a peer set, a transport, and the knobs that
 * control what this client sends and plays back.
 *
 * A [MediaEngineConnection] is created by [MediaEngine.connect] once the voice control socket has
 * told us where the dedicated media server is, and it is destroyed when that socket drops. It does
 * not survive a reconnect — [com.discord.rtcconnection.RtcConnection] builds a new one.
 *
 * Reference: `com.discord.rtcconnection.mediaengine.MediaEngineConnection`. The reference's method
 * names were erased by R8; the names here are taken from the surviving parameter names and the
 * native calls each one makes.
 */
interface MediaEngineConnection {

    /** `DEFAULT` for a voice channel, `STREAM` for a Go Live broadcast. */
    val type: Type

    // region Peers

    /**
     * Announces a remote participant, or updates the ssrcs of one already announced.
     *
     * The engine cannot decode a peer's RTP until it has been told which ssrcs belong to which
     * user, and [isMuted]/[volume] have to be supplied here rather than applied afterwards so a
     * locally-muted user never gets one audible packet through on join.
     */
    fun connectUser(userId: Long, audioSsrc: Int, videoSsrc: Int?, isMuted: Boolean, volume: Float)

    fun setUserMuted(userId: Long, mute: Boolean)

    fun isUserMuted(userId: Long): Boolean

    /** Playout volume as a percentage; `100` is unity. */
    fun setUserPlayoutVolume(userId: Long, volume: Float)

    /** Stops decoding a peer's video entirely (the user hid the tile). */
    fun setUserVideoDisabled(userId: Long, disabled: Boolean)

    fun isUserVideoDisabled(userId: Long): Boolean

    /**
     * Marks a peer's video as scrolled out of view.
     *
     * Distinct from [setUserVideoDisabled]: off-screen is a transient layout fact that only lowers
     * the quality we ask that sender for, whereas disabled is a user decision.
     */
    fun setUserOffScreen(userId: Long, offScreen: Boolean)

    fun isUserOffScreen(userId: Long): Boolean

    // endregion

    // region Local state

    fun setSelfMute(selfMute: Boolean)

    fun setSelfDeafen(selfDeaf: Boolean)

    fun setPttActive(active: Boolean)

    fun setInputMode(inputMode: InputMode, inputModeOptions: InputModeOptions)

    fun setVideoBroadcast(isVideoBroadcast: Boolean)

    fun setEncodingQuality(quality: EncodingQuality)

    /** `true` when the Krisp noise canceller is engaged for this connection. */
    fun isNoiseCancellationEnabled(): Boolean

    // endregion

    // region Screen share

    /**
     * Starts broadcasting the screen using an already-granted media-projection [permission].
     *
     * [thumbnailEmitter], when supplied, renders periodic preview bitmaps off the same capture
     * pipeline so the stream preview does not need a second capture session.
     */
    fun startScreenshare(permission: Intent, thumbnailEmitter: ThumbnailEmitter? = null)

    fun stopScreenshare()

    // endregion

    // region Transport negotiation

    /** Applies the RTP encryption mode and secret key from the server's `SELECT_PROTOCOL_ACK`. */
    fun setEncryptionSettings(mode: String, secretKey: IntArray)

    /**
     * Resolves the codec names the server chose against the codecs this device advertised, and
     * configures the encoder/decoder pair.
     *
     * Failure here is reported to [Listener.onError] with
     * [FailedConnectionException.FailureType.CODEC_NEGOTIATION_FAILED] rather than thrown, because
     * it happens on the socket thread mid-handshake.
     */
    fun selectCodecs(audioCodec: String, videoCodec: String)

    // endregion

    // region DAVE

    fun connectUsers(userIds: List<String>) = Unit
    fun destroyUser(userId: String) = Unit
    fun prepareSecureFramesTransition(transitionId: Int, protocolVersion: Int, callback: () -> Unit) = Unit
    fun executeSecureFramesTransition(transitionId: Int) = Unit
    fun prepareSecureFramesEpoch(epoch: String, transitionId: Int, groupId: String) = Unit
    fun getMLSKeyPackageB64(callback: (String) -> Unit) = Unit
    fun updateMLSExternalSenderB64(externalSender: String) = Unit
    fun processMLSProposalsB64(proposals: String, callback: (String) -> Unit) = Unit
    fun prepareMLSCommitTransitionB64(
        transitionId: Int,
        commit: String,
        callback: (Boolean, Int, String) -> Unit,
    ) = Unit
    fun processMLSWelcomeB64(
        transitionId: Int,
        welcome: String,
        callback: (Boolean, Int, String) -> Unit,
    ) = Unit
    fun setOnMLSFailureCallback(callback: (String, String) -> Unit) = Unit
    fun setSecureFramesStateUpdateCallback(callback: (String) -> Unit) = Unit

    // endregion

    fun addListener(listener: Listener)

    fun removeListener(listener: Listener)

    /** Asks the engine for a stats snapshot; the callback arrives on the engine thread. */
    fun getStats(onStats: (Stats) -> Unit)

    /** Reports a Krisp CPU-overuse verdict to every listener. */
    fun notifyKrispStatus(status: KrispOveruseDetector.Status)

    fun destroy()

    /**
     * Lifecycle of the *media* transport, as distinct from the control socket's own state.
     *
     * Declaration order matters: the reference maps these ordinals straight onto
     * `RtcConnection.State`.
     */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        NO_ROUTE,
    }

    /** `DEFAULT` is the voice channel connection; `STREAM` is a second connection for Go Live. */
    enum class Type {
        DEFAULT,
        STREAM,
    }

    class FailedConnectionException(
        message: String?,
        val type: FailureType = FailureType.UNKNOWN,
    ) : Exception(message) {

        enum class FailureType {
            DISCONNECTED_BEFORE_CONNECTION_ESTABLISHED,
            TIMEOUT,
            ADDRESS_IP_RESOLVED,
            NO_CONNECTION_INFO,
            CODEC_NEGOTIATION_FAILED,
            UNKNOWN,
            ;

            /**
             * The first three are expected outcomes of a race — the user left, the network moved —
             * and are only breadcrumbed. The rest are real failures and raise an analytics event.
             */
            val isBenign: Boolean
                get() = this == DISCONNECTED_BEFORE_CONNECTION_ESTABLISHED ||
                    this == TIMEOUT ||
                    this == ADDRESS_IP_RESOLVED
        }
    }

    /**
     * How the microphone gate works. [numeral] is the value the native engine expects, not the
     * ordinal, so the two must not be conflated.
     */
    enum class InputMode(val numeral: Int) {
        VOICE_ACTIVITY(1),
        PUSH_TO_TALK(2),
    }

    /**
     * Tuning for [InputMode].
     *
     * The defaults are the reference's: a 10-frame lead-in buffer so the first syllable is not
     * clipped, 40 frames of trailing audio so a pause mid-sentence does not close the gate, and an
     * auto threshold rather than the fixed [vadThreshold].
     */
    data class InputModeOptions(
        val vadThreshold: Int = 0,
        val vadLeadingFrames: Int = 10,
        val vadTrailingFrames: Int = 40,
        val vadAutoThreshold: Boolean = true,
        val vadUseKrisp: Boolean = true,
        val pttReleaseDelayMs: Int = 5,
    )

    /** Encoder envelope for the local video track. */
    data class EncodingQuality(
        val minBitrate: Int,
        val maxBitrate: Int,
        val width: Int,
        val height: Int,
        val mutedFramerate: Int,
        val framerate: Int,
    )

    /** Where the engine actually bound its socket, reported back to the voice server. */
    data class TransportInfo(
        val address: String,
        val port: Int,
        val protocol: Protocol,
    ) {
        enum class Protocol { UDP, TCP }
    }

    interface Listener {
        fun onConnected(
            connection: MediaEngineConnection,
            transportInfo: TransportInfo,
            supportedVideoCodecs: List<Codec>,
        )

        fun onConnectionStateChange(connection: MediaEngineConnection, connectionState: ConnectionState)

        fun onDestroy(connection: MediaEngineConnection)

        fun onError(connection: MediaEngineConnection, exception: FailedConnectionException)

        fun onKrispStatus(connection: MediaEngineConnection, status: KrispOveruseDetector.Status)

        fun onLocalMute(userId: Long, muted: Boolean)

        fun onLocalVideoOffScreen(userId: Long, offScreen: Boolean)

        fun onSpeaking(userId: Long, ssrc: Int, speaking: Boolean)

        fun onTargetBitrate(bitrate: Int)

        fun onTargetFrameRate(frameRate: Int)

        fun onVideo(
            userId: Long,
            streamId: Int?,
            audioSsrc: Int,
            videoSsrc: Int,
            rtxSsrc: Int,
            streams: Array<StreamParameters>,
        )
    }

    /** Every callback defaulted except [onDestroy], matching the reference's abstract adapter. */
    abstract class DefaultListener : Listener {
        override fun onConnected(
            connection: MediaEngineConnection,
            transportInfo: TransportInfo,
            supportedVideoCodecs: List<Codec>,
        ) = Unit

        override fun onConnectionStateChange(
            connection: MediaEngineConnection,
            connectionState: ConnectionState,
        ) = Unit

        abstract override fun onDestroy(connection: MediaEngineConnection)

        override fun onError(connection: MediaEngineConnection, exception: FailedConnectionException) = Unit

        override fun onKrispStatus(connection: MediaEngineConnection, status: KrispOveruseDetector.Status) = Unit

        override fun onLocalMute(userId: Long, muted: Boolean) = Unit

        override fun onLocalVideoOffScreen(userId: Long, offScreen: Boolean) = Unit

        override fun onSpeaking(userId: Long, ssrc: Int, speaking: Boolean) = Unit

        override fun onTargetBitrate(bitrate: Int) = Unit

        override fun onTargetFrameRate(frameRate: Int) = Unit

        override fun onVideo(
            userId: Long,
            streamId: Int?,
            audioSsrc: Int,
            videoSsrc: Int,
            rtxSsrc: Int,
            streams: Array<StreamParameters>,
        ) = Unit
    }
}
