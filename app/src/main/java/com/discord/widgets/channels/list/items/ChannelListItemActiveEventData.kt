package com.discord.widgets.channels.list.items

import com.discord.models.guild.UserGuildMember
import com.discord.widgets.guildscheduledevent.GuildScheduledEventLocationInfo

data class ChannelListItemActiveEventData(
    val topic: String?,
    val locationInfo: GuildScheduledEventLocationInfo,
    val audienceSize: Int,
    val speakers: List<UserGuildMember>,
    val connected: Boolean,
    val isSpeaker: Boolean,
    val eventId: Long?,
)
