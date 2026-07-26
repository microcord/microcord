package com.discord.stores

import com.discord.api.voice.state.VoiceState
import com.discord.rtcconnection.audio.DiscordAudioManager
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

/**
 * Mirrors the platform audio router into store state.
 *
 * The `AudioManager` callbacks arrive on their own thread, so every mutation is bounced through the
 * dispatcher before it becomes observable state — see [updateAudioDevices] / [updateActiveAudioDevice].
 * Routing *commands* go the other way, straight to [audioOutputController].
 *
 * [lastActiveAudioDevice] deliberately ignores `DEFAULT` and `INVALID`: it is what the speaker toggle
 * falls back to, and falling back to "unspecified" would do nothing.
 */
class StoreAudioManagerV2(
    private val dispatcher: Dispatcher,
    private val videoUseDetector: VideoUseDetector,
    private val audioOutputController: AudioOutputController = AudioOutputController.NoOp,
    /** `StoreVoiceChannelSelected.getSelectedVoiceChannelId()`; that store does not exist yet. */
    private val getSelectedVoiceChannelId: () -> Long = { 0L },
    /** Applies the media-volume slider to a live stream. `StoreStreamRtcConnection.updateStreamVolume`. */
    private val updateStreamVolume: (Float) -> Unit = {},
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    data class State(
        var audioDevices: List<DiscordAudioManager.AudioDevice> = DiscordAudioManager.defaultAudioDevices(),
        var activeAudioDevice: DiscordAudioManager.DeviceTypes = DiscordAudioManager.DeviceTypes.INVALID,
    )

    /**
     * Whether *we* are publishing video in a channel.
     *
     * Only our own track matters: the earpiece is forced available while our camera is on so a video
     * call can be held to the ear, and released again when it is off.
     */
    class VideoUseDetector(
        private val channelsStore: StoreChannels,
        private val voiceStatesStore: StoreVoiceStates,
        private val usersStore: StoreUser,
    ) {
        @StoreThread
        private fun getVoiceStatesForChannel(channelId: Long): Map<Long, VoiceState> {
            val guildId = channelsStore.findChannelById(channelId)?.guildId ?: return emptyMap()
            return voiceStatesStore.getInternal()[guildId]
                ?.filterValues { it.channelId == channelId }
                .orEmpty()
        }

        @StoreThread
        fun isMyVideoOn(channelId: Long): Boolean {
            val voiceState = getVoiceStatesForChannel(channelId)[usersStore.getMeInternal().id]
                ?: return false
            return voiceState.selfStream || voiceState.selfVideo
        }
    }

    internal var state: State = State()
        private set
    private var stateSnapshot: State = state

    internal var lastActiveAudioDevice: DiscordAudioManager.AudioDevice =
        DiscordAudioManager.AudioDevice(DiscordAudioManager.DeviceTypes.SPEAKERPHONE)
        private set

    private var prevMyVideoOn: Boolean = false

    private fun getAudioManagerState(): State = stateSnapshot

    @StoreThread
    internal fun updateActiveAudioDevice(device: DiscordAudioManager.DeviceTypes) {
        state = state.copy(activeAudioDevice = device)
        if (device != DiscordAudioManager.DeviceTypes.INVALID &&
            device != DiscordAudioManager.DeviceTypes.DEFAULT
        ) {
            state.audioDevices.getOrNull(device.value)?.let { lastActiveAudioDevice = it }
        }
        markChanged()
    }

    @StoreThread
    internal fun updateAudioDevices(devices: List<DiscordAudioManager.AudioDevice>) {
        state = state.copy(audioDevices = devices)
        markChanged()
    }

    /**
     * Keeps a watched stream at the same perceived loudness as the system media slider.
     *
     * The system slider is linear in index; loudness is not, so the ratio is converted through the
     * perceptual curve the media engine uses before it reaches the stream gain.
     */
    @StoreThread
    internal fun updateCurrentMediaVolume(currentMediaVolumeIndex: Int) {
        val maxVolume = audioOutputController.maxMediaVolume
        if (maxVolume <= 0) return
        val ratio = currentMediaVolumeIndex.toFloat() / maxVolume
        updateStreamVolume(perceptualToAmplitude(ratio * MAX_STREAM_VOLUME, MAX_STREAM_VOLUME))
    }

    /** Enters in-call audio mode while connected so routing and volume keys follow the call. */
    @StoreThread
    fun handleRtcConnectionState(isConnected: Boolean) = audioOutputController.setCallActive(isConnected)

    @StoreThread
    fun handleVoiceStatesUpdated() {
        val myVideoOn = videoUseDetector.isMyVideoOn(getSelectedVoiceChannelId())
        if (myVideoOn == prevMyVideoOn) return
        audioOutputController.setEarpieceForceAvailable(myVideoOn)
        prevMyVideoOn = myVideoOn
    }

    fun observeAudioManagerState(): Observable<State> =
        observationDeck.connectRx(this) { getAudioManagerState() }

    fun selectOutputDevice(device: DiscordAudioManager.DeviceTypes) =
        audioOutputController.selectOutputDevice(device)

    /**
     * Speaker on/off.
     *
     * "Off" means the highest-priority *other* available device rather than a fixed earpiece, so a
     * connected headset or Bluetooth device wins.
     */
    fun toggleSpeakerOutput() = dispatcher.schedule {
        if (state.activeAudioDevice == DiscordAudioManager.DeviceTypes.SPEAKERPHONE) {
            val fallback = DEVICE_PRIORITY.lastOrNull { device ->
                device != DiscordAudioManager.DeviceTypes.SPEAKERPHONE &&
                    state.audioDevices.getOrNull(device.value)?.isAvailable == true
            } ?: DiscordAudioManager.DeviceTypes.EARPIECE
            selectOutputDevice(fallback)
        } else {
            selectOutputDevice(DiscordAudioManager.DeviceTypes.SPEAKERPHONE)
        }
    }

    fun updateMediaVolume(ratio: Float) = audioOutputController.setMediaVolume(ratio)

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        // State holds `var` fields, so the snapshot must copy the device list too — publishing the
        // live list would let the audio thread mutate what an observer is already rendering.
        stateSnapshot = state.copy(audioDevices = state.audioDevices.map { it.copy() })
    }

    companion object {
        val DEFAULT_STATE = State()

        /** Highest stream gain the media engine accepts, as a percentage. */
        private const val MAX_STREAM_VOLUME = 300f

        /**
         * Routing preference, lowest to highest. `toggleSpeakerOutput` scans it backwards, so a
         * Bluetooth headset beats a wired one, which beats the earpiece.
         */
        private val DEVICE_PRIORITY = listOf(
            DiscordAudioManager.DeviceTypes.SPEAKERPHONE,
            DiscordAudioManager.DeviceTypes.EARPIECE,
            DiscordAudioManager.DeviceTypes.WIRED_HEADSET,
            DiscordAudioManager.DeviceTypes.BLUETOOTH_HEADSET,
        )

        /**
         * Reference `com.discord.utilities.voice.PerceptualVolumeUtils.perceptualToAmplitude`.
         *
         * A volume slider is linear in *perceived* loudness; the engine wants amplitude. Below the
         * nominal maximum the slider spans a 50 dB range ending at 0 dB, above it a 6 dB boost
         * range. **Belongs in `utilities/voice`** and is kept private here only because that package
         * is owned elsewhere.
         */
        private fun perceptualToAmplitude(perceptual: Float, normalizedMax: Float): Float {
            if (perceptual <= 0f) return 0f
            val decibels = if (perceptual > normalizedMax) {
                (perceptual - normalizedMax) / normalizedMax * VOLUME_BOOST_DYNAMIC_RANGE_DB
            } else {
                perceptual / normalizedMax * VOLUME_DYNAMIC_RANGE_DB - VOLUME_DYNAMIC_RANGE_DB
            }
            return normalizedMax * Math.pow(10.0, (decibels / 20f).toDouble()).toFloat()
        }

        private const val VOLUME_BOOST_DYNAMIC_RANGE_DB = 6f
        private const val VOLUME_DYNAMIC_RANGE_DB = 50f
    }
}
