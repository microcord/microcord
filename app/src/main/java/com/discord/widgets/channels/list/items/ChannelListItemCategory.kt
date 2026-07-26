package com.discord.widgets.channels.list.items

import com.discord.api.channel.Channel

data class ChannelListItemCategory(
    val channel: Channel,
    val isCollapsed: Boolean,
    val isMuted: Boolean,
    val canManageChannels: Boolean,
) : ChannelListItem {
    override val type: Int = ChannelListItem.TYPE_CATEGORY
    override val key: String = "$type${channel.id}"
}
