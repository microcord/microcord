package com.discord.stores

import com.discord.api.voice.state.StageRequestToSpeakState
import com.discord.api.voice.state.VoiceState
import com.discord.rtcconnection.audio.DiscordAudioManager

/**
 * Seams the voice/media store layer needs from the RTC transport, which belongs to Phase 11.
 *
 * Everything in this file has a real home in `com.discord.rtcconnection` in the reference. It lives
 * here only because the transport package is still a shim; when Phase 11 reconstructs
 * `com.discord.rtcconnection`, these declarations move there unchanged and the stores keep
 * compiling against the same shapes.
 *
 * Nothing here is invented: each type mirrors the reference declaration cited on it.
 */

/**
 * Per-user video track description reported by the media engine.
 *
 * Reference: `com.discord.rtcconnection.VideoMetadata`. **Move to that package in Phase 11.**
 */
data class VideoMetadata(
    val userID: Long,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFrameRate: Int?,
    val type: ResolutionType,
)

/**
 * How a video track's resolution is negotiated.
 *
 * Reference: `com.discord.rtcconnection.socket.io.Payloads.ResolutionType`, wire values `source` /
 * `fixed`, declared in that order. **Move to that package in Phase 11.**
 */
enum class ResolutionType(val apiValue: String) {
    Source("source"),
    Fixed("fixed"),
}

/**
 * The audio-routing side effects [StoreAudioManagerV2] drives.
 *
 * The reference calls `DiscordAudioManager` singleton methods directly from the store; that class is
 * still a data-only shim here, so the store takes this instead. Phase 11 implements it on top of the
 * real `DiscordAudioManager` and can then delete the seam.
 */
interface AudioOutputController {
    /** Routes to [device], honouring availability. Reference: `DiscordAudioManager.setDevice`. */
    fun selectOutputDevice(device: DiscordAudioManager.DeviceTypes)

    /** Enters/leaves the in-call audio mode. Reference: `DiscordAudioManager.setCallActive`. */
    fun setCallActive(active: Boolean)

    /** Sets the media stream volume from a 0..1 ratio. Reference: `DiscordAudioManager.setMediaVolume`. */
    fun setMediaVolume(ratio: Float)

    /**
     * Marks the earpiece available or not. The reference forces it available while the local camera
     * is on so a video call can be held to the ear.
     */
    fun setEarpieceForceAvailable(forceAvailable: Boolean)

    /** The device index whose volume slider [setMediaVolume] addresses; used to normalise volumes. */
    val maxMediaVolume: Int

    object NoOp : AudioOutputController {
        override fun selectOutputDevice(device: DiscordAudioManager.DeviceTypes) = Unit
        override fun setCallActive(active: Boolean) = Unit
        override fun setMediaVolume(ratio: Float) = Unit
        override fun setEarpieceForceAvailable(forceAvailable: Boolean) = Unit
        override val maxMediaVolume: Int get() = 0
    }
}

/**
 * `true` when this voice state means "drop this user", i.e. it carries no channel.
 *
 * The gateway signals a leave by sending a voice state whose `channel_id` is null rather than by
 * sending a delete, so every voice cache treats this as a removal.
 *
 * Reference: `VoiceState.isRemoveSignal` (`b.c.a.a0.d.X0`), `channelId == null`.
 */
val VoiceState.isRemoveSignal: Boolean
    get() = channelId == null

/**
 * Stage speaking state derived from a voice state.
 *
 * Reference: `VoiceState.getStageRequestToSpeakState` (`b.c.a.a0.d.y0`):
 * - suppressed **with** a request timestamp -> [StageRequestToSpeakState.REQUESTED_TO_SPEAK]
 * - not suppressed **with** a request timestamp -> the moderator invited us and we have not
 *   acknowledged yet ([StageRequestToSpeakState.REQUESTED_TO_SPEAK_AND_AWAITING_USER_ACK])
 * - no timestamp -> [StageRequestToSpeakState.ON_STAGE] when not suppressed, else
 *   [StageRequestToSpeakState.NONE]
 */
val VoiceState?.stageRequestToSpeakState: StageRequestToSpeakState
    get() = when {
        this == null -> StageRequestToSpeakState.NONE
        requestToSpeakTimestamp == null ->
            if (!suppress) StageRequestToSpeakState.ON_STAGE else StageRequestToSpeakState.NONE
        suppress -> StageRequestToSpeakState.REQUESTED_TO_SPEAK
        else -> StageRequestToSpeakState.REQUESTED_TO_SPEAK_AND_AWAITING_USER_ACK
    }
