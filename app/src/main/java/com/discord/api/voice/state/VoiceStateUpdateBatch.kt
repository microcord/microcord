package com.discord.api.voice.state

/**
 * Voice state changes the gateway coalesced into one dispatch.
 *
 * Each entry is an ordinary voice state, so the batch is expanded and replayed through the
 * unbatched path — including the member hydration a state carries for a user never seen before.
 */
data class VoiceStateUpdateBatch(val voiceStates: List<VoiceState> = emptyList())
