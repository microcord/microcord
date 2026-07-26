package com.discord.api.guildscheduledevent

/** Whether the viewer may broadcast an event, and whether a broadcast is already running. */
data class GuildScheduledEventBroadcast(
    val canBroadcast: Boolean,
    val hasBroadcast: Boolean?,
)

/** Body of the start-broadcast request; [entityId] names the channel to broadcast into. */
data class GuildScheduledEventBroadcastBody(
    val type: Int,
    val entityId: Long?,
)

/** The nickname and avatar overrides shown for an RSVP'd member. */
data class GuildScheduledEventRsvpGuildMember(
    val avatar: String?,
    val nick: String?,
)
