package com.discord.widgets.channels.list.items

import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.models.guild.Guild

data class ChannelListItemGuildScheduledEvents(
    val data: List<GuildScheduledEvent>,
    val guild: Guild,
) : ChannelListItem {
    override val type: Int = ChannelListItem.TYPE_GUILD_SCHEDULED_EVENTS
    override val key: String = type.toString()
}
