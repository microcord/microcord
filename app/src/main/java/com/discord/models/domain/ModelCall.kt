package com.discord.models.domain

import com.discord.api.voice.state.VoiceState

/**
 * Payload of `CALL_CREATE` / `CALL_UPDATE` / `CALL_DELETE`: the state of a private or group call.
 *
 * [unavailable] marks a call whose voice region could not be reached. The collection properties are
 * nullable because the backend omits them rather than sending an empty array; use [ringingUserIds]
 * for the always-present view.
 */
data class ModelCall(
    val channelId: Long,
    val messageId: Long = 0L,
    val region: String? = null,
    val unavailable: Boolean = false,
    val ringing: List<Long>? = null,
    val voiceStates: List<VoiceState>? = null,
) {
    /** User ids currently being rung; empty when the call is not ringing anyone. */
    val ringingUserIds: List<Long> get() = ringing.orEmpty()

    /** Response body describing whether a channel may be rung. */
    data class Ringable(val ringable: Boolean)
}
