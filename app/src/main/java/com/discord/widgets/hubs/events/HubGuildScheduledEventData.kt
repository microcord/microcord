package com.discord.widgets.hubs.events

import com.discord.api.channel.Channel
import com.discord.api.guildscheduledevent.GuildScheduledEvent

data class HubGuildScheduledEventData(
    val directoryChannelId: Long,
    val event: GuildScheduledEvent,
    val channel: Channel?,
    val isRsvped: Boolean,
    val canShare: Boolean,
    val canStartEvent: Boolean,
    val canConnect: Boolean,
    val isConnected: Boolean,
    val isInGuild: Boolean,
)
