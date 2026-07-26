package com.discord.api.guildscheduledevent

/** Payload of `GUILD_SCHEDULED_EVENT_USER_ADD` and `GUILD_SCHEDULED_EVENT_USER_REMOVE`. */
data class GuildScheduledEventUserUpdate(
    val guildScheduledEventId: Long,
    val guildId: Long,
    val userId: Long,
)
