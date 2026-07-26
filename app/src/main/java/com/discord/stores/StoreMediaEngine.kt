package com.discord.stores

import co.discord.media_engine.VideoInputDeviceDescription
import co.discord.media_engine.VideoInputDeviceFacing
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelRtcLatencyRegion
import com.discord.utilities.persister.Persister
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
 * **Phase 11 boundary.** [mediaEngineController] stands in for the JNI bridge. Device *state* and
 * every observation surface are real; the calls that reach `libdiscord.so` are routed through the
 * seam and are no-ops until the engine is reconstructed.
 */
class StoreMediaEngine(
    private val mediaEngineController: MediaEngineController = MediaEngineController.NoOp,
) : Store() {

    /** The JNI-facing surface this store drives. Reconstructed in Phase 11. */
    interface MediaEngineController {
        /** Asks the engine to enumerate cameras; the callback may arrive on an engine thread. */
        fun getVideoInputDevices(callback: (List<VideoInputDeviceDescription>) -> Unit)

        /** Selects the camera at [index], or `-1` to release the current one. */
        fun setVideoInputDevice(index: Int)

        /** Enables or disables local video capture on the voice connection. */
        fun setVideoInputEnabled(enabled: Boolean)

        fun setAudioInputEnabled(enabled: Boolean)

        fun setPttActive(active: Boolean)

        /** Measures round-trip latency to each region and returns them best-first. */
        fun rankRtcRegions(
            regions: List<ModelRtcLatencyRegion>,
            callback: (List<String>) -> Unit,
        )

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
    private val selectedVideoInputDeviceSubject =
        BehaviorSubject.create<VideoInputDeviceDescription?>(null)

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
     * Re-enumerating with the preferred guid while the channel id is `0` is what actually hands the
     * camera back to the system: the engine holds it open otherwise, and no other app can use it.
     */
    @Synchronized
    fun handleVoiceChannelSelected(channelId: Long) {
        val previous = previousVoiceChannelId
        if (previous != null && previous != NO_VOICE_CHANNEL && channelId == NO_VOICE_CHANNEL) {
            mediaEngineController.getVideoInputDevices { devices ->
                handleVideoInputDevices(devices, preferredVideoInputDeviceGUID)
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
        val index = devices.indexOfFirst { it.guid == deviceGUID }
        val hasDevice = index >= 0

        mediaEngineController.setVideoInputDevice(NO_VIDEO_DEVICE)
        mediaEngineController.setVideoInputDevice(index)
        mediaEngineController.setVideoInputEnabled(hasDevice)

        onSelected?.invoke(if (hasDevice) deviceGUID else null)

        selectedVideoInputDevice = if (hasDevice) devices[index] else null
        selectedVideoInputDeviceSubject.onNext(selectedVideoInputDevice)

        videoInputDevices = devices
        videoInputDevicesSubject.onNext(devices)

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
