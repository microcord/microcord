package com.discord.widgets.channels.list.items

import com.discord.api.channel.Channel

data class ChannelListItemDirectory(
    val channel: Channel,
    val selected: Boolean,
    val unreadCount: Int,
) : ChannelListItem {
    override val type: Int = ChannelListItem.TYPE_DIRECTORY
    override val key: String = "$type${channel.id}"
}
