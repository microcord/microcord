package com.discord.widgets.channels.list.items

/**
 * The "Invite people" row. Hubs report a different [type] because the row copy, icon and
 * destination differ, but both variants come from this one item.
 */
data class ChannelListItemInvite(
    val guildId: Long,
    val isHub: Boolean,
) : ChannelListItem {
    override val type: Int =
        if (isHub) ChannelListItem.TYPE_HUB_INVITE else ChannelListItem.TYPE_INVITE
}
