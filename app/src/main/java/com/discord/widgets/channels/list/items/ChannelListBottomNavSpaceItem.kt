package com.discord.widgets.channels.list.items

/** Trailing spacer so the last row can scroll clear of the bottom navigation bar. */
class ChannelListBottomNavSpaceItem(val guildId: Long = 0L) : ChannelListItem {
    override val type: Int = ChannelListItem.TYPE_BOTTOM_NAV_SPACE
    override val key: String = "$type$guildId"
}
