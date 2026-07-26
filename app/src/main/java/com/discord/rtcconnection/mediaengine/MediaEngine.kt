package com.discord.rtcconnection.mediaengine

import androidx.annotation.AnyThread
import co.discord.media_engine.RtcRegion
import co.discord.media_engine.StreamParameters
import co.discord.media_engine.VideoInputDeviceDescription
import com.hammerandchisel.libdiscord.Discord
import rx.Observable

/**
 * Process-wide handle on the native media engine.
 *
 * There is exactly one of these; it owns the `libdiscord.so` instance, the camera, the microphone
 * and every live [MediaEngineConnection]. Creating it is lazy — the engine is only constructed the
 * first time something actually needs media — because construction loads a large native library and
 * grabs the audio subsystem.
 *
 * Reference: `com.discord.rtcconnection.mediaengine.MediaEngine`.
 */
interface MediaEngine {

    /** The single thread every native call must be made on. */
    val mediaEngineThreadExecutor: MediaEngineThreadExecutor

    /**
     * Brings the engine up and enumerates the device's video codecs.
     *
     * Completes immediately when that has already happened, errors when the native library could
     * not be initialised at all. A connection cannot be built before this completes, because the
     * codec list is what `SELECT_PROTOCOL` offers.
     */
    fun prepare(): Observable<Unit>

    /** Measures round-trip latency to each region's probe IPs and returns them best-first. */
    fun rankRtcRegions(regionsWithIps: Array<RtcRegion>, callback: (Array<String>) -> Unit)

    /** Applies mute/deafen/volume/AEC/noise settings to the engine and every live connection. */
    @AnyThread
    fun updateVoiceConfig(voiceConfig: VoiceConfig)

    /** The audio subsystem and layer the engine actually selected, for diagnostics. */
    fun observeAudioInfo(): Observable<AudioInfo>

    /** Selects the camera at [deviceIndex]; `-1` releases the current one. */
    fun setVideoInputDevice(deviceIndex: Int)

    fun getVideoInputDevices(devicesCallback: (Array<VideoInputDeviceDescription>) -> Unit)

    /** Master microphone gate. `false` stops capture entirely rather than sending silence. */
    fun setAudioInputEnabled(enabled: Boolean)

    /** Subscribes to local mic level updates, or `null` to stop them. */
    fun setLocalVoiceStatusListener(voiceStatusListener: ((LocalVoiceStatus) -> Unit)?)

    /** Chooses how the OpenSL ES device list is interpreted; must be set before the engine starts. */
    fun setOpenSLUsageMode(openSLUsageMode: OpenSLUsageMode)

    /**
     * Opens a media session to the server described by [options].
     *
     * Returns `null` when the engine is not usable; [onFailure] receives the reason when the
     * failure is a caller error rather than a missing engine.
     */
    fun connect(
        userId: Long,
        options: ConnectionOptions,
        type: MediaEngineConnection.Type,
        onFailure: (Exception) -> Unit,
    ): MediaEngineConnection?

    fun getConnections(): List<MediaEngineConnection>

    /** The raw JNI bridge, for the video sink plumbing that needs it directly. */
    fun getNativeEngine(): Discord?

    /** Where the media server for one connection lives, plus the layers we intend to send. */
    data class ConnectionOptions(
        val ssrc: Int,
        val ip: String,
        val port: Int,
        val streams: List<StreamParameters>,
    )

    data class AudioInfo(val audioSubsystem: String, val audioLayer: String)

    /** Local microphone level, `0f..1f`, and whether the gate is currently open. */
    data class LocalVoiceStatus(val amplitude: Float, val isSpeaking: Boolean)

    /**
     * Everything the settings layer controls about capture and playback.
     *
     * Reference: `MediaEngine.VoiceConfig`; `StoreMediaSettings.VoiceConfiguration` maps onto it.
     */
    data class VoiceConfig(
        val outputVolume: Float,
        val echoCancellation: Boolean,
        val noiseSuppression: Boolean,
        val noiseCancellation: Boolean,
        val automaticGainControl: Boolean,
        val inputMode: MediaEngineConnection.InputMode,
        val inputModeOptions: MediaEngineConnection.InputModeOptions,
        val isSelfDeafened: Boolean,
        val isSelfMuted: Boolean,
    )

    /**
     * What the engine ended up doing about echo cancellation, which is not simply what was asked
     * for: a device may have a usable hardware canceller, a broken one, or none.
     */
    data class EchoCancellationInfo(
        val builtinAecRequested: Boolean = false,
        val builtinAecSupportedNative: Boolean = false,
        val builtinAecSupportedJava: Boolean = false,
        val builtinAecEnabled: Boolean = false,
        val aecEnabledInSettings: Boolean = false,
        val aecEnabledInNativeConfig: Boolean = false,
        val aecMobileMode: Boolean = false,
        val aecEnabledByDefault: Boolean = false,
        val aecMobileModeByDefault: Boolean = false,
    )

    /** Experiment override for the OpenSL ES audio path. */
    enum class OpenSLESConfig { DEFAULT, FORCE_ENABLED, FORCE_DISABLED }

    /** Whether the packaged device list is an allow list or an exclude list. */
    enum class OpenSLUsageMode { ALLOW_LIST, EXCLUDE_LIST }

    fun interface EchoCancellationCallback {
        fun onEchoCancellationUpdated(info: EchoCancellationInfo)
    }

    /** Engine-level lifecycle, distinct from any one connection's. */
    interface Listener {
        fun onConnected()
        fun onConnecting()
        fun onNativeEngineInitialized()
        fun onNewConnection(connection: MediaEngineConnection)
    }
}
