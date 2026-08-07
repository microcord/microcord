package com.discord.widgets.channels.list.items

import com.discord.api.channel.Channel
import com.discord.widgets.channels.list.WidgetChannelListUnreads

data class ChannelListItemTextChannel(
    val channel: Channel,
    val selected: Boolean,
    override val mentionCount: Int,
    override val isUnread: Boolean,
    override val isUnreadImportant: Boolean,
    val isMentionLowImportance: Boolean,
    val muted: Boolean,
    val isLocked: Boolean,
    val hasActiveThreads: Boolean,
    val isGuildRoleSubscriptionLockedChannel: Boolean,
    val isGuildRoleSubscriptionChannel: Boolean,
) : ChannelListItem, WidgetChannelListUnreads.UnreadItem {
    override val type: Int = ChannelListItem.TYPE_CHANNEL
    override val key: String = "$type${channel.id}"
}
