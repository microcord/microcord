package com.discord.models.domain

import com.discord.api.voice.state.VoiceState

/**
 * Payload of `CALL_CREATE` / `CALL_UPDATE` / `CALL_DELETE`: the state of a private or group call.
 *
 * [ringing] holds the user ids currently being rung; [unavailable] marks a call whose voice region
 * could not be reached.
 */
data class ModelCall(
    val channelId: Long,
    val messageId: Long = 0L,
    val region: String? = null,
    val unavailable: Boolean = false,
    val ringing: List<Long> = emptyList(),
    val voiceStates: List<VoiceState>? = null,
) {
    /** Response body describing whether a channel may be rung. */
    data class Ringable(val ringable: Boolean)
}
