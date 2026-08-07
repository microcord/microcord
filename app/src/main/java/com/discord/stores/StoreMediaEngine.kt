package com.discord.stores

import co.discord.media_engine.VideoInputDeviceDescription
import co.discord.media_engine.VideoInputDeviceFacing
import co.discord.media_engine.VideoFrameCallback
import co.discord.media_engine.VideoOutputSink
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelRtcLatencyRegion
import com.discord.rtcconnection.mediaengine.MediaEngineThreadExecutor
import com.discord.utilities.persister.Persister
import org.webrtc.VideoCapturer
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject

/**
 * Store-side view of the native media engine: camera selection, push-to-talk, and init state.
 *
 * Two facts are persisted rather than recomputed:
 * - [PREFERRED_VIDEO_INPUT_DEVICE_GUID_CACHE_KEY] — the camera the user last chose, so a video call
 *   opens on the same one rather than resetting to front-facing every time.
 * - [NATIVE_ENGINE_EVER_INITIALIZED_CACHE_KEY] — whether the native engine has *ever* come up on
 *   this install. Features that would otherwise probe the engine (the RTC latency test) gate on it,
 *   so a device where the engine cannot load never pays for the attempt.
 *
 * Every mutator is `@Synchronized`: device enumeration completes on an engine callback thread while
 * the UI is free to call `selectVideoInputDevice` from the main thread.
 *
 * Native calls stay behind [MediaEngineController] so ordering can be tested without loading JNI.
 */
class StoreMediaEngine(
    mediaEngineController: MediaEngineController = MediaEngineController.NoOp,
) : Store(), VideoOutputSink {

    @Volatile
    private var mediaEngineController = mediaEngineController

    /** The JNI-facing surface this store drives. */
    interface MediaEngineController {
        val executor: MediaEngineThreadExecutor? get() = null
        val maxDaveProtocolVersion: Int get() = 0

        /** Asks the engine to enumerate cameras; the callback may arrive on an engine thread. */
        fun getVideoInputDevices(callback: (List<VideoInputDeviceDescription>) -> Unit)

        /** Selects the camera at [index], or `-1` to release the current one. */
        fun setVideoInputDevice(index: Int)

        fun setVideoInputDevice(deviceGuid: String) = Unit

        /** Enables or disables local video capture on the voice connection. */
        fun setVideoInputEnabled(enabled: Boolean)

        fun setAudioInputEnabled(enabled: Boolean)

        fun setPttActive(active: Boolean)

        fun getCodecCapabilities(callback: (List<CodecCapability>) -> Unit) = callback(emptyList())

        /** Measures round-trip latency to each region and returns them best-first. */
        fun rankRtcRegions(
            regions: List<ModelRtcLatencyRegion>,
            callback: (List<String>) -> Unit,
        )

        fun createVoiceConnection(
            userId: Long,
            options: ConnectionOptions,
            callback: (ConnectionInfo, String) -> Unit,
        ): Connection? = null

        fun setVideoOutputSink(streamId: String, callback: VideoFrameCallback?) = Unit

        fun dispose() = Unit

        data class ConnectionOptions(
            val context: String,
            val address: String,
            val port: Int,
            val ssrc: Int,
            val experiments: List<String>,
            val modes: List<String>,
            val streamParameters: List<StreamParameters>,
            val qosEnabled: Boolean,
        )

        data class StreamParameters(
            val type: String,
            val rid: String,
            val ssrc: Int,
            val maxBitrate: Int,
            val soundshare: Boolean? = null,
            val quality: Int? = null,
            val maxResolution: MaxResolution? = null,
            val maxPixelCount: Int? = null,
            val rtxSsrc: Int? = null,
            val maxFrameRate: Int? = null,
            val active: Boolean? = null,
        ) {
            data class MaxResolution(val type: String, val width: Int, val height: Int)
        }

        data class ConnectionInfo(
            val isConnected: Boolean,
            val protocol: String,
            val localAddress: String,
            val localPort: Int,
        )

        data class CodecCapability(
            val codec: String,
            val decode: Boolean,
            val encode: Boolean,
        )

        interface Connection {
            fun dispose()
            fun getEncryptionModes(callback: (List<String>) -> Unit)
            fun mergeUsers(usersJson: String)
            fun destroyUser(userId: String)
            fun setVideoBroadcast(enabled: Boolean)
            fun setLocalVolume(userId: String, volume: Float)
            fun setLocalMute(userId: String, muted: Boolean)
            fun setSelfMute(muted: Boolean)
            fun setSelfDeafen(deafened: Boolean)
            fun setPTTActive(active: Boolean, priority: Boolean, muteOverride: Boolean)
            fun setOnSpeaking(callback: (String, Int, Float) -> Unit)
            fun setOnPing(callback: (Int, String, Int, Int) -> Unit)
            fun setOnPingTimeout(callback: (String, Int, Int, Int) -> Unit)
            fun fastUdpReconnect()
            fun setOnVideo(callback: (String, Long, String, String) -> Unit)
            fun setOnFirstFrame(callback: (String, Long, String) -> Unit)
            fun startBroadcast(capturer: VideoCapturer, soundshareNativeInstance: Long)
            fun stopBroadcast()
            fun prepareSecureFramesTransition(transitionId: Int, protocolVersion: Int, callback: () -> Unit)
            fun executeSecureFramesTransition(transitionId: Int)
            fun prepareSecureFramesEpoch(epoch: String, transitionId: Int, groupId: String)
            fun getMLSKeyPackageB64(callback: (String) -> Unit)
            fun updateMLSExternalSenderB64(externalSenderB64: String)
            fun processMLSProposalsB64(proposals: String, callback: (String) -> Unit)
            fun prepareMLSCommitTransitionB64(
                transitionId: Int,
                commit: String,
                callback: (Boolean, Int, String) -> Unit,
            )
            fun processMLSWelcomeB64(
                transitionId: Int,
                welcome: String,
                callback: (Boolean, Int, String) -> Unit,
            )
            fun setOnMLSFailure(callback: (String, String) -> Unit)
            fun setSecureFramesStateUpdate(callback: (String) -> Unit)
        }

        object NoOp : MediaEngineController {
            override fun getVideoInputDevices(callback: (List<VideoInputDeviceDescription>) -> Unit) =
                callback(emptyList())
            override fun setVideoInputDevice(index: Int) = Unit
            override fun setVideoInputEnabled(enabled: Boolean) = Unit
            override fun setAudioInputEnabled(enabled: Boolean) = Unit
            override fun setPttActive(active: Boolean) = Unit
            override fun rankRtcRegions(
                regions: List<ModelRtcLatencyRegion>,
                callback: (List<String>) -> Unit,
            ) = callback(regions.map(ModelRtcLatencyRegion::region))
        }
    }

    private val hasNativeEngineEverInitializedCache =
        Persister(NATIVE_ENGINE_EVER_INITIALIZED_CACHE_KEY, false)
    private var hasNativeEngineEverInitialized = false

    private val preferredVideoInputDeviceGuidCache =
        Persister(PREFERRED_VIDEO_INPUT_DEVICE_GUID_CACHE_KEY, DEFAULT_VIDEO_DEVICE_GUID)
    private var preferredVideoInputDeviceGUID: String = DEFAULT_VIDEO_DEVICE_GUID

    private val isNativeEngineInitializedSubject =
        SerializedSubject<Boolean, Boolean>(BehaviorSubject.create(false))
    private val pttActiveSubject = SerializedSubject<Boolean, Boolean>(BehaviorSubject.create(false))

    private var videoInputDevices: List<VideoInputDeviceDescription> = emptyList()
    private val videoInputDevicesSubject =
        BehaviorSubject.create<List<VideoInputDeviceDescription>>(emptyList())

    private var selectedVideoInputDevice: VideoInputDeviceDescription? = null
    private val selectedVideoInputDeviceSubject: BehaviorSubject<VideoInputDeviceDescription?> =
        BehaviorSubject.create(null as VideoInputDeviceDescription?)

    private var userId: Long = NO_USER
    private var previousVoiceChannelId: Long? = null

    @Synchronized
    fun init() {
        hasNativeEngineEverInitialized = hasNativeEngineEverInitializedCache.get()
        preferredVideoInputDeviceGUID = preferredVideoInputDeviceGuidCache.get()
    }

    @Synchronized
    fun handleConnectionOpen(payload: ModelPayload) {
        userId = payload.me?.id ?: NO_USER
    }

    /**
     * Releases the camera when we leave voice.
     *
     * Selecting no device while the channel id is `0` hands the camera back to the system.
     */
    @Synchronized
    fun handleVoiceChannelSelected(channelId: Long) {
        val previous = previousVoiceChannelId
        if (previous != null && previous != NO_VOICE_CHANNEL && channelId == NO_VOICE_CHANNEL) {
            mediaEngineController.getVideoInputDevices { devices ->
                handleVideoInputDevices(devices, null)
            }
        }
        previousVoiceChannelId = channelId
    }

    /** Reported by the engine the first time it comes up; persisted so it survives a restart. */
    @Synchronized
    fun handleNativeEngineInitialized() {
        isNativeEngineInitializedSubject.onNext(true)
        if (hasNativeEngineEverInitialized) return
        hasNativeEngineEverInitialized = true
        hasNativeEngineEverInitializedCache.set(true, true)
    }

    @Synchronized
    internal fun handleNewConnection() {
        mediaEngineController.getVideoInputDevices { devices ->
            handleVideoInputDevices(devices, selectedVideoInputDevice?.guid)
        }
    }

    @Synchronized
    fun hasNativeEngineEverInitialized(): Boolean = hasNativeEngineEverInitialized

    fun getIsNativeEngineInitialized(): Observable<Boolean> = isNativeEngineInitializedSubject

    fun observePttActive(): Observable<Boolean> = pttActiveSubject

    fun getVideoInputDevices(): Observable<List<VideoInputDeviceDescription>> =
        videoInputDevicesSubject.distinctUntilChanged()

    fun getSelectedVideoInputDevice(): Observable<VideoInputDeviceDescription?> =
        selectedVideoInputDeviceSubject.distinctUntilChanged()

    @Synchronized
    fun getSelectedVideoInputDeviceBlocking(): VideoInputDeviceDescription? = selectedVideoInputDevice

    fun getRankedRtcRegions(
        regionsWithIps: List<ModelRtcLatencyRegion>,
        callback: (List<String>) -> Unit,
    ) = mediaEngineController.rankRtcRegions(regionsWithIps, callback)

    fun selectVideoInputDevice(deviceGUID: String?) =
        mediaEngineController.getVideoInputDevices { devices ->
            handleVideoInputDevices(devices, deviceGUID)
        }

    /**
     * Picks the camera to open with: the remembered one when it is still present, otherwise the
     * front-facing one, otherwise whatever exists.
     */
    fun selectDefaultVideoDevice(onSelected: ((String?) -> Unit)? = null) =
        mediaEngineController.getVideoInputDevices { devices ->
            handleVideoInputDevices(devices, devices.pickDefaultDeviceGUID(), onSelected)
        }

    /** Front/back toggle. Wraps around, and does nothing when the current device is unknown. */
    @Synchronized
    fun cycleVideoInputDevice() {
        val index = videoInputDevices.indexOf(selectedVideoInputDevice)
        if (index < 0) return
        val next = if (index == videoInputDevices.lastIndex) 0 else index + 1
        selectVideoInputDevice(videoInputDevices[next].guid)
    }

    @Synchronized
    fun setAudioInputEnabled(audioInputEnabled: Boolean) =
        mediaEngineController.setAudioInputEnabled(audioInputEnabled)

    @Synchronized
    fun setPttActive(active: Boolean) {
        mediaEngineController.setPttActive(active)
        pttActiveSubject.onNext(active)
    }

    override fun setVideoOutputSink(streamId: String, callback: VideoFrameCallback?) =
        mediaEngineController.setVideoOutputSink(streamId, callback)

    @Synchronized
    fun dispose() = mediaEngineController.dispose()

    /**
     * Applies a device enumeration.
     *
     * The engine is always reset to `-1` first: selecting a new index without releasing the old one
     * leaves both cameras open on some devices. A guid that is no longer present resolves to "no
     * device", which also disables local video so the connection stops publishing a dead track.
     */
    @Synchronized
    private fun handleVideoInputDevices(
        devices: List<VideoInputDeviceDescription>,
        deviceGUID: String?,
        onSelected: ((String?) -> Unit)? = null,
    ) {
        val availableDevices = devices.ifEmpty { videoInputDevices }
        val index = availableDevices.indexOfFirst { it.guid == deviceGUID }
        val hasDevice = index >= 0

        mediaEngineController.setVideoInputDevice(NO_VIDEO_DEVICE)
        mediaEngineController.setVideoInputDevice(
            if (hasDevice) availableDevices[index].guid else DEFAULT_VIDEO_DEVICE_GUID,
        )
        mediaEngineController.setVideoInputEnabled(hasDevice)

        onSelected?.invoke(if (hasDevice) deviceGUID else null)

        selectedVideoInputDevice = if (hasDevice) availableDevices[index] else null
        selectedVideoInputDeviceSubject.onNext(selectedVideoInputDevice)

        videoInputDevices = availableDevices
        videoInputDevicesSubject.onNext(availableDevices)

        selectedVideoInputDevice?.let { device ->
            preferredVideoInputDeviceGUID = device.guid
            preferredVideoInputDeviceGuidCache.set(device.guid, true)
        }
    }

    private fun List<VideoInputDeviceDescription>.pickDefaultDeviceGUID(): String? =
        if (any { it.guid == preferredVideoInputDeviceGUID }) {
            preferredVideoInputDeviceGUID
        } else {
            (firstOrNull { it.facing == VideoInputDeviceFacing.Front } ?: firstOrNull())?.guid
        }

    private companion object {
        private const val NATIVE_ENGINE_EVER_INITIALIZED_CACHE_KEY =
            "CACHE_KEY_NATIVE_ENGINE_EVER_INITIALIZED"
        private const val PREFERRED_VIDEO_INPUT_DEVICE_GUID_CACHE_KEY =
            "PREFERRED_VIDEO_INPUT_DEVICE_GUID"
        private const val DEFAULT_VIDEO_DEVICE_GUID = ""
        private const val NO_VIDEO_DEVICE = -1
        private const val NO_VOICE_CHANNEL = 0L
        private const val NO_USER = -1L
    }
}
