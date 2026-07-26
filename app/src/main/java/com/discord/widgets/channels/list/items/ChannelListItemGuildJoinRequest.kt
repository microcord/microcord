package com.discord.widgets.channels.list.items

import com.discord.api.guildjoinrequest.GuildJoinRequest
import com.discord.models.member.GuildMember

/** Membership-screening application status card, pinned above the channel list. */
data class ChannelListItemGuildJoinRequest(
    val guildJoinRequest: GuildJoinRequest,
    val member: GuildMember,
) : ChannelListItem {
    override val type: Int = ChannelListItem.TYPE_GUILD_JOIN_REQUEST
    override val key: String = type.toString()
}
