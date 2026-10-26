package com.discord.api.channel

/** One channel in a `CHANNEL_INFO` reply; only the fields that were requested are present. */
data class ChannelMetadata(
    val id: Long,
    val status: String? = null,
    /** Unix seconds when the channel's current voice session began; absent when nobody is in it. */
    val voiceStartTime: Long? = null,
)

/** Payload of `CHANNEL_INFO`, the reply to the gateway's `REQUEST_CHANNEL_INFO`. */
data class ChannelInfo(
    val guildId: Long,
    val channels: List<ChannelMetadata>,
)

/** Payload of `VOICE_CHANNEL_STATUS_UPDATE`; a null [status] clears it. */
data class VoiceChannelStatusUpdate(
    val id: Long,
    val guildId: Long,
    val status: String? = null,
)

/** Payload of `VOICE_CHANNEL_START_TIME_UPDATE`; a null start time means the session ended. */
data class VoiceChannelStartTimeUpdate(
    val id: Long,
    val guildId: Long,
    val voiceStartTime: Long? = null,
)
