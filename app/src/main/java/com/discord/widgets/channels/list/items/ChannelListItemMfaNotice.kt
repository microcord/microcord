package com.discord.widgets.channels.list.items

/**
 * Shown at the top of a guild that requires elevated MFA which this session does not have,
 * i.e. `GuildChannelsInfo.unelevated`.
 */
class ChannelListItemMfaNotice : ChannelListItem {
    override val type: Int = ChannelListItem.TYPE_MFA_NOTICE

    override fun equals(other: Any?): Boolean = other is ChannelListItemMfaNotice
    override fun hashCode(): Int = type
    override fun toString(): String = "ChannelListItemMfaNotice"
}
