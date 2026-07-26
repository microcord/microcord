package com.discord.api.embeddedactivities

/** Payload of `EMBEDDED_ACTIVITY_UPDATE`. */
data class EmbeddedActivityInboundUpdate(
    val channelId: Long,
    val embeddedActivity: EmbeddedActivity,
    val guildId: Long,
    val users: List<Long>,
)
