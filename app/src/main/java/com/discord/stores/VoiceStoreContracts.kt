package com.discord.stores

import com.discord.models.domain.ModelApplicationStream
import com.discord.rtcconnection.audio.DiscordAudioManager
import com.discord.rtcconnection.mediaengine.MediaEngineConnection

class StoreApplicationStreaming private constructor() {
    data class ActiveApplicationStream(
        val state: State,
        val stream: ModelApplicationStream,
    ) {
        enum class State {
            CONNECTING,
            ACTIVE,
            RECONNECTING,
            ENDED,
            PAUSED,
            DENIED_FULL;

            fun isStreamActive() = this in ACTIVE_STATES

            companion object {
                val ACTIVE_STATES = listOf(CONNECTING, ACTIVE, RECONNECTING, ENDED)
            }
        }
    }
}

class StoreAudioManagerV2 private constructor() {
    data class State(
        var audioDevices: List<DiscordAudioManager.AudioDevice> = DiscordAudioManager.defaultAudioDevices(),
        var activeAudioDevice: DiscordAudioManager.DeviceTypes = DiscordAudioManager.DeviceTypes.INVALID,
    )
}

class StoreMediaSettings private constructor() {
    enum class NoiseProcessing {
        None,
        Suppression,
        Cancellation,
        CancellationTemporarilyDisabled,
    }

    enum class SelfMuteFailure { CANNOT_USE_VAD }

    enum class VadUseKrisp {
        Disabled,
        Enabled,
        TemporarilyDisabled,
    }

    data class VoiceConfiguration(
        val isSelfMuted: Boolean = false,
        val isSelfDeafened: Boolean = false,
        val automaticVad: Boolean = true,
        val vadUseKrisp: VadUseKrisp = VadUseKrisp.Enabled,
        val automaticGainControl: Boolean = true,
        val echoCancellation: Boolean = true,
        val noiseProcessing: NoiseProcessing = NoiseProcessing.Cancellation,
        val sensitivity: Float = DEFAULT_SENSITIVITY,
        val inputMode: MediaEngineConnection.InputMode = MediaEngineConnection.InputMode.VOICE_ACTIVITY,
        val outputVolume: Float = DEFAULT_OUTPUT_VOLUME,
        val mutedUsers: Map<Long, Boolean> = emptyMap(),
        val userOutputVolumes: Map<Long, Float> = emptyMap(),
        val offScreenUsers: Map<Long, Boolean> = emptyMap(),
        val enableVideoHardwareScaling: Boolean = false,
        val voiceParticipantsHidden: Boolean = false,
    ) {
        companion object {
            const val DEFAULT_OUTPUT_VOLUME = 100f
            const val DEFAULT_SENSITIVITY = -50f
            val DEFAULT_VOICE_CONFIG = VoiceConfiguration()
            val DEFAULT_NOISE_PROCESSING = DEFAULT_VOICE_CONFIG.noiseProcessing.ordinal
        }
    }
}
