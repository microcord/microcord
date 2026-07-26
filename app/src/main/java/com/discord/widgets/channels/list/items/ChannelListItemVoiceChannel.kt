package com.discord.widgets.channels.list.items

import com.discord.api.channel.Channel
import com.discord.api.guild.GuildMaxVideoChannelUsers
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.widgets.channels.list.WidgetChannelListUnreads

data class ChannelListItemVoiceChannel(
    override val channel: Channel,
    val textSelected: Boolean,
    val voiceSelected: Boolean,
    val permission: Long?,
    override val mentionCount: Int,
    override val isUnread: Boolean,
    override val numUsersConnected: Int,
    val isLocked: Boolean,
    val isNsfw: Boolean,
    val isAnyoneUsingVideo: Boolean,
    val guildMaxVideoChannelUsers: GuildMaxVideoChannelUsers,
    val isGuildRoleSubscriptionLockedChannel: Boolean,
    val isGuildRoleSubscriptionChannel: Boolean,
    val guildScheduledEvent: GuildScheduledEvent?,
) : ChannelListItem, ChannelListVocalItem, WidgetChannelListUnreads.UnreadItem {
    override val type: Int = ChannelListItem.TYPE_CHANNEL_VOICE
    override val key: String = "$type${channel.id}"
}
