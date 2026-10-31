package com.discord.widgets.channels.list.items

import com.discord.models.guild.Guild

/**
 * RN's guild header actions: search the server, invite people and the events list. Replaces the
 * reference's standalone events row.
 */
data class ChannelListItemGuildActions(
    val guild: Guild,
    val canInvite: Boolean,
    /** False when there are no events and the viewer cannot create one. */
    val showEvents: Boolean,
) : ChannelListItem {
    override val type: Int = ChannelListItem.TYPE_GUILD_ACTIONS
}
