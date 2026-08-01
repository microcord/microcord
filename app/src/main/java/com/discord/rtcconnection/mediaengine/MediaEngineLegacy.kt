package com.discord.rtcconnection.mediaengine

import android.content.Context
import androidx.annotation.AnyThread
import co.discord.media_engine.RtcRegion
import co.discord.media_engine.VideoInputDeviceDescription
import com.discord.app.AppLog
import com.discord.rtcconnection.Codec
import com.hammerandchisel.libdiscord.Discord
import java.util.Locale
import org.webrtc.Logging
import org.webrtc.audio.JavaAudioDeviceModule
import rx.Emitter
import rx.Observable

/**
 * [MediaEngine] on top of `com.hammerandchisel.libdiscord.Discord`.
 *
 * The native engine is created lazily by [ensureNativeEngine] and never destroyed for the lifetime
 * of the process: creating it costs an audio-subsystem probe and a library load, and the reference
 * keeps one instance alive across calls for the same reason.
 *
 * Reference: `b.a.q.m0.c.k` (`MediaEngineLegacy`).
 */
class MediaEngineLegacy(
    private val context: Context,
    private val listener: MediaEngine.Listener,
    override val mediaEngineThreadExecutor: MediaEngineThreadExecutor,
    private val openSLESConfig: MediaEngine.OpenSLESConfig,
    private val echoCancellation: EchoCancellationConfig,
    private val echoCancellationCallback: MediaEngine.EchoCancellationCallback,
) : MediaEngine {
    override val maxDaveProtocolVersion: Int
        get() = nativeEngine?.maxSupportedProtocolVersion ?: 0


    private val noiseCancellationConfig = NoiseCancellationConfig()
    private val connections = mutableListOf<MediaEngineConnection>()

    private var nativeEngine: Discord? = null
    private var audioInputEnabled: Boolean = true
    private var openSLUsageMode: MediaEngine.OpenSLUsageMode = MediaEngine.OpenSLUsageMode.ALLOW_LIST

    /** `null` until the device's video codecs have been enumerated; that is what "prepared" means. */
    private var supportedVideoCodecs: List<Codec>? = null

    /**
     * Half-built AEC report. The native engine answers the "is there a built-in canceller" question
     * and the "did the software canceller turn on" question through two different callbacks, so the
     * first one parks its half here for the second one to complete.
     */
    private var pendingEchoCancellationInfo: MediaEngine.EchoCancellationInfo? = null

    override fun prepare(): Observable<Unit> {
        if (supportedVideoCodecs != null) return Observable.just(Unit)
        ensureNativeEngine()
        val engine = nativeEngine
            ?: return Observable.error(IllegalStateException("Failed to initialize native media engine"))

        return Observable
            .create<List<Codec>>({ emitter ->
                engine.getSupportedVideoCodecs { codecs -> emitter.onNext(rankVideoCodecs(codecs)); emitter.onCompleted() }
            }, Emitter.BackpressureMode.NONE)
            .doOnNext { codecs -> supportedVideoCodecs = codecs }
            .map { Unit }
    }

    /**
     * Turns the engine's codec names into RTP payload types.
     *
     * H.264 is sorted first because it is the only codec every Discord client can decode in
     * hardware; the payload types (`101/102`, `103/104`, …) are derived from that order so both
     * ends agree without an extra negotiation round trip.
     */
    private fun rankVideoCodecs(videoCodecNames: Array<String>): List<Codec> =
        videoCodecNames
            .map { it.uppercase(Locale.ROOT) }
            .sortedBy { it != "H264" }
            .mapIndexed { index, name ->
                val payloadType = index * 2 + FIRST_VIDEO_PAYLOAD_TYPE
                Codec(
                    name = name,
                    priority = index + 1,
                    type = "video",
                    payloadType = payloadType,
                    rtxPayloadType = payloadType + 1,
                    encode = Discord.codecCapabilities[name]?.encode,
                    decode = Discord.codecCapabilities[name]?.decode,
                )
            }

    override fun rankRtcRegions(regionsWithIps: Array<RtcRegion>, callback: (Array<String>) -> Unit) {
        ensureNativeEngine()
        nativeEngine?.getRankedRtcRegions(regionsWithIps) { regions -> callback(regions) }
    }

    @AnyThread
    override fun updateVoiceConfig(voiceConfig: MediaEngine.VoiceConfig) {
        mediaEngineThreadExecutor.run { applyVoiceConfig(voiceConfig) }
    }

    private fun applyVoiceConfig(voiceConfig: MediaEngine.VoiceConfig) {
        AppLog.d(TAG, "updateVoiceConfig: $voiceConfig")
        val engine = nativeEngine

        // The engine takes 0..1; settings expose 0..300 so a quiet speaker can be boosted.
        engine?.setSpeakerVolume(voiceConfig.outputVolume.coerceIn(0f, MAX_OUTPUT_VOLUME) / MAX_VOLUME_PERCENT)

        mediaEngineThreadExecutor.assertOnEngineThread()
        val resolvedAec = echoCancellation.resolveSoftwareAec(voiceConfig.echoCancellation)
        if (resolvedAec != voiceConfig.echoCancellation) {
            AppLog.d(
                TAG,
                "ignoring call to setEchoCancellation(${voiceConfig.echoCancellation}), " +
                    "config=$echoCancellation",
            )
        }
        engine?.setEchoCancellation(resolvedAec, false) { requestEnable, requestMobileMode, enabled, previouslyEnabled, previouslyMobileMode ->
            mediaEngineThreadExecutor.run {
                onEchoCancellationConfigured(
                    requestEnable = requestEnable,
                    requestMobileMode = requestMobileMode,
                    enabled = enabled,
                    previouslyEnabled = previouslyEnabled,
                    previouslyMobileMode = previouslyMobileMode,
                )
            }
        }

        engine?.setNoiseSuppression(voiceConfig.noiseSuppression)
        noiseCancellationConfig.enabled = voiceConfig.noiseCancellation
        engine?.setNoiseCancellation(voiceConfig.noiseCancellation)
        engine?.setAutomaticGainControl(voiceConfig.automaticGainControl)

        getConnections().forEach { connection ->
            connection.setInputMode(voiceConfig.inputMode, voiceConfig.inputModeOptions)
            connection.setSelfDeafen(voiceConfig.isSelfDeafened)
            // A Go Live connection is always muted: its audio comes from the screen capture, not
            // from the microphone, and un-muting it would double the speaker's voice.
            connection.setSelfMute(
                voiceConfig.isSelfMuted || connection.type == MediaEngineConnection.Type.STREAM,
            )
        }
    }

    private fun onEchoCancellationConfigured(
        requestEnable: Boolean,
        requestMobileMode: Boolean,
        enabled: Boolean,
        previouslyEnabled: Boolean,
        previouslyMobileMode: Boolean,
    ) {
        val builtin = pendingEchoCancellationInfo ?: return
        pendingEchoCancellationInfo = null
        val info = builtin.copy(
            aecEnabledInSettings = requestEnable,
            aecEnabledInNativeConfig = enabled,
            aecMobileMode = requestMobileMode,
            aecEnabledByDefault = previouslyEnabled,
            aecMobileModeByDefault = previouslyMobileMode,
        )
        AppLog.d(TAG, "onEchoCancellationUpdated: $info")
        echoCancellationCallback.onEchoCancellationUpdated(info)
    }

    override fun observeAudioInfo(): Observable<MediaEngine.AudioInfo> = Observable.create(
        { emitter ->
            nativeEngine?.getAudioSubsystem { audioSubsystem, audioLayer ->
                emitter.onNext(MediaEngine.AudioInfo(audioSubsystem, audioLayer))
            }
        },
        Emitter.BackpressureMode.LATEST,
    )

    override fun setVideoInputDevice(deviceIndex: Int) {
        nativeEngine?.setVideoInputDevice(deviceIndex)
    }

    override fun getVideoInputDevices(devicesCallback: (Array<VideoInputDeviceDescription>) -> Unit) {
        ensureNativeEngine()
        nativeEngine?.getVideoInputDevices { devices -> devicesCallback(devices) }
    }

    override fun setAudioInputEnabled(enabled: Boolean) {
        audioInputEnabled = enabled
        nativeEngine?.setAudioInputEnabled(enabled)
    }

    override fun setLocalVoiceStatusListener(voiceStatusListener: ((MediaEngine.LocalVoiceStatus) -> Unit)?) {
        if (voiceStatusListener == null) {
            nativeEngine?.setLocalVoiceLevelChangedCallback(null)
            return
        }
        ensureNativeEngine()
        nativeEngine?.setLocalVoiceLevelChangedCallback { level, speaking ->
            // `speaking` is a bit field; bit 0 is "the gate is open".
            voiceStatusListener(MediaEngine.LocalVoiceStatus(level, speaking and SPEAKING_FLAG != 0))
        }
    }

    override fun setOpenSLUsageMode(openSLUsageMode: MediaEngine.OpenSLUsageMode) {
        if (nativeEngine != null) {
            AppLog.e(TAG, "setting openSLUsageMode too late")
        }
        this.openSLUsageMode = openSLUsageMode
    }

    @Synchronized
    override fun connect(
        userId: Long,
        options: MediaEngine.ConnectionOptions,
        type: MediaEngineConnection.Type,
        onFailure: (Exception) -> Unit,
    ): MediaEngineConnection? {
        val codecs = supportedVideoCodecs
        if (codecs == null) {
            onFailure(IllegalStateException("connect() called on unprepared media engine."))
            return null
        }
        val engine = nativeEngine
        if (engine == null) {
            AppLog.e(TAG, "connect() called without voiceEngineLegacy.")
            return null
        }

        mediaEngineThreadExecutor.run { notifyListener { it.onConnecting() } }
        AppLog.i(TAG, "Connecting with options: $options")

        val connection = MediaEngineConnectionLegacy(
            executor = mediaEngineThreadExecutor,
            noiseCancellationConfig = noiseCancellationConfig,
            nativeEngine = engine,
            supportedVideoCodecs = codecs,
            type = type,
            userId = userId,
            connectionOptions = options,
            listeners = listOf(ConnectionLifecycleListener()),
        )
        connections += connection
        notifyListener { it.onNewConnection(connection) }
        return connection
    }

    @Synchronized
    override fun getConnections(): List<MediaEngineConnection> = connections.toList()

    override fun getNativeEngine(): Discord? = nativeEngine

    @Synchronized
    private fun onConnectionDestroyed(connection: MediaEngineConnection) {
        // The local voice level callback is engine-wide, not per connection; leaving it installed
        // after the last connection dies keeps the microphone meter running with nothing to feed.
        nativeEngine?.setLocalVoiceLevelChangedCallback(null)
        connections -= connection
    }

    /**
     * Forwards connection lifecycle to the engine-level [MediaEngine.Listener], and drops the
     * connection from [connections] once it is finished.
     *
     * Every one of the three ways a connection can end — an explicit destroy, a `DISCONNECTED`
     * transition, or an error — removes it, because only the first of those is guaranteed to
     * happen for any given failure.
     */
    private inner class ConnectionLifecycleListener : MediaEngineConnection.DefaultListener() {
        override fun onConnected(
            connection: MediaEngineConnection,
            transportInfo: MediaEngineConnection.TransportInfo,
            supportedVideoCodecs: List<Codec>,
        ) {
            mediaEngineThreadExecutor.run { notifyListener { it.onConnected() } }
        }

        override fun onConnectionStateChange(
            connection: MediaEngineConnection,
            connectionState: MediaEngineConnection.ConnectionState,
        ) {
            if (connectionState == MediaEngineConnection.ConnectionState.DISCONNECTED) {
                mediaEngineThreadExecutor.run { onConnectionDestroyed(connection) }
            }
        }

        override fun onDestroy(connection: MediaEngineConnection) {
            mediaEngineThreadExecutor.run { onConnectionDestroyed(connection) }
        }

        override fun onError(
            connection: MediaEngineConnection,
            exception: MediaEngineConnection.FailedConnectionException,
        ) {
            mediaEngineThreadExecutor.run { onConnectionDestroyed(connection) }
        }
    }

    /**
     * Creates the native engine if it does not exist yet.
     *
     * Every failure mode is caught: the library may be missing for this ABI
     * ([UnsatisfiedLinkError]), its static initialiser may fail
     * ([ExceptionInInitializerError]), or the audio subsystem may throw. None of them may take the
     * process down — a device that cannot do voice must still be able to read messages.
     */
    private fun ensureNativeEngine() {
        if (nativeEngine != null) return

        AppLog.i(TAG, "initializing voice engine. OpenSL ES: $openSLESConfig, OpenSL usage mode: $openSLUsageMode")
        // Modern WebRTC selects its audio backend internally and no longer exposes an OpenSL override.
        Logging.externalReporter = Logging.ExternalReporter { tag, message, throwable ->
            AppLog.e(tag, message, throwable)
        }

        try {
            val engine = Discord(context, Discord.LOGLEVEL_DEBUG)
            if (echoCancellation.shouldEarlyEnableHwAec) {
                engine.enableBuiltInAEC(true) { requested, nativeSupported, enabled ->
                    mediaEngineThreadExecutor.run {
                        echoCancellation.isHwAecEnabled = enabled
                        pendingEchoCancellationInfo = MediaEngine.EchoCancellationInfo(
                            builtinAecRequested = requested,
                            builtinAecSupportedNative = nativeSupported,
                            builtinAecSupportedJava = JavaAudioDeviceModule
                                .isBuiltInAcousticEchoCancelerSupported(),
                            builtinAecEnabled = enabled,
                        )
                    }
                }
            }
            engine.setAudioInputEnabled(audioInputEnabled)
            nativeEngine = engine
        } catch (e: ExceptionInInitializerError) {
            AppLog.e(TAG, "Unable to initialize voice engine.", e)
        } catch (e: UnsatisfiedLinkError) {
            AppLog.e(TAG, "Unable to initialize voice engine.", e)
        } catch (t: Throwable) {
            AppLog.e(TAG, "Unable to initialize voice engine, new error discovered", t)
        }

        if (nativeEngine != null) notifyListener { it.onNativeEngineInitialized() }
    }

    private inline fun notifyListener(block: (MediaEngine.Listener) -> Unit) {
        try {
            block(listener)
        } catch (e: Exception) {
            AppLog.e(TAG, "Error in listener", e)
        }
    }

    companion object {
        private const val TAG = "MediaEngineLegacy"
        private const val FIRST_VIDEO_PAYLOAD_TYPE = 101
        private const val MAX_OUTPUT_VOLUME = 300f
        private const val MAX_VOLUME_PERCENT = 100f
        private const val SPEAKING_FLAG = 1

    }
}
