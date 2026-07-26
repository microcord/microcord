package com.discord.widgets.voice

import co.discord.media_engine.VideoInputDeviceDescription
import com.discord.rtcconnection.RtcConnection
import com.discord.rtcconnection.audio.DiscordAudioManager
import com.discord.stores.StoreAudioManagerV2
import com.discord.stores.StoreMediaEngine
import com.discord.stores.StoreRtcConnection
import rx.Observable

/**
 * The voice UI's access point to the three RTC-transport stores.
 *
 * `StoreStream` publishes accessors for the nine voice *state* stores (`getVoiceParticipants`,
 * `getMediaSettings`, `getApplicationStreaming`, …) and the UI calls those directly. It does not
 * construct [StoreMediaEngine], [StoreAudioManagerV2] or [StoreRtcConnection] — those belong to the
 * RTC transport, which is being reconstructed separately — so the transport installs them here
 * instead, following the same pattern `StoreStream` already uses for
 * `WidgetChatListAdapterItemCallMessage.voiceParticipants`.
 *
 * **Required wiring:** whoever owns the transport must call
 * `VoiceStoreAccess.install(VoiceStoreAccess.Stores(mediaEngine, audioManagerV2, rtcConnection))`
 * once the graph is built.
 *
 * Until then the accessors below fall back to a documented "no camera, no RTC session, default
 * audio routing" reading, so every voice screen renders and navigates correctly and only the
 * camera, audio-output and connection-quality affordances are inert. Nothing crashes and nothing
 * silently lies: [isInstalled] tells a caller which of the two worlds it is in.
 *
 * Delete this object once `StoreStream` owns all three stores.
 */
object VoiceStoreAccess {

    class Stores(
        val mediaEngine: StoreMediaEngine,
        val audioManagerV2: StoreAudioManagerV2,
        val rtcConnection: StoreRtcConnection,
    )

    @Volatile
    private var stores: Stores? = null

    /** Whether the RTC transport has installed its stores. */
    @JvmStatic
    val isInstalled: Boolean get() = stores != null

    @JvmStatic
    fun install(stores: Stores) {
        this.stores = stores
    }

    @JvmStatic
    fun getOrNull(): Stores? = stores

    // ---- Media engine -------------------------------------------------------------------------

    /** Cameras the device offers. Empty without a media engine, which reads as "no camera". */
    fun observeVideoInputDevices(): Observable<List<VideoInputDeviceDescription>> =
        stores?.mediaEngine?.getVideoInputDevices() ?: Observable.just(emptyList())

    /** The selected camera; `null` means the camera is off. */
    fun observeSelectedVideoInputDevice(): Observable<VideoInputDeviceDescription?> =
        stores?.mediaEngine?.getSelectedVideoInputDevice() ?: Observable.just(null)

    fun getSelectedVideoInputDeviceBlocking(): VideoInputDeviceDescription? =
        stores?.mediaEngine?.getSelectedVideoInputDeviceBlocking()

    /** Whether push-to-talk is currently held down. */
    fun observePttActive(): Observable<Boolean> =
        stores?.mediaEngine?.observePttActive() ?: Observable.just(false)

    fun setPttActive(active: Boolean) {
        stores?.mediaEngine?.setPttActive(active)
    }

    fun selectVideoInputDevice(deviceGuid: String?) {
        stores?.mediaEngine?.selectVideoInputDevice(deviceGuid)
    }

    fun selectDefaultVideoDevice() {
        stores?.mediaEngine?.selectDefaultVideoDevice()
    }

    fun cycleVideoInputDevice() {
        stores?.mediaEngine?.cycleVideoInputDevice()
    }

    /** Turns the local camera on or off; a no-op while the transport is absent. */
    fun setVideoEnabled(enabled: Boolean) {
        val mediaEngine = stores?.mediaEngine ?: return
        if (enabled) mediaEngine.selectDefaultVideoDevice() else mediaEngine.selectVideoInputDevice(null)
    }

    // ---- Audio routing ------------------------------------------------------------------------

    /** Audio devices and the active route. Falls back to the store's own default state. */
    fun observeAudioManagerState(): Observable<StoreAudioManagerV2.State> =
        stores?.audioManagerV2?.observeAudioManagerState()
            ?: Observable.just(StoreAudioManagerV2.DEFAULT_STATE)

    fun selectOutputDevice(device: DiscordAudioManager.DeviceTypes) {
        stores?.audioManagerV2?.selectOutputDevice(device)
    }

    fun toggleSpeakerOutput() {
        stores?.audioManagerV2?.toggleSpeakerOutput()
    }

    // ---- RTC connection -----------------------------------------------------------------------

    /** Identity of the live RTC session; `null` means "not in a call". */
    fun observeRtcConnectionMetadata(): Observable<RtcConnection.Metadata?> =
        stores?.rtcConnection?.observeRtcConnectionMetadata() ?: Observable.just(null)

    fun getRtcConnectionMetadata(): RtcConnection.Metadata? =
        stores?.rtcConnection?.getRtcConnectionMetadata()

    /** Whether the media socket is up. */
    fun observeRtcConnectionState(): Observable<Boolean> =
        stores?.rtcConnection?.connectionState ?: Observable.just(false)
}
