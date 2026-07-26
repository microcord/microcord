package com.discord.api.voice.server

/**
 * Payload of `VOICE_SERVER_UPDATE`.
 *
 * Exactly one of [guildId] / [channelId] is present: guild voice carries the guild, private calls
 * carry the channel.
 */
data class VoiceServer(
    val guildId: Long?,
    val channelId: Long?,
    val endpoint: String?,
    val token: String?,
) {
    override fun toString(): String =
        "VoiceServer(guildId=$guildId, channelId=$channelId, endpoint=$endpoint, token=<redacted>)"
}
