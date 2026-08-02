package com.discord.widgets.channels.list.items

import com.discord.api.channel.Channel
import com.discord.api.user.Nameplate
import com.discord.models.presence.Presence
import com.discord.widgets.channels.list.WidgetChannelListUnreads

data class ChannelListItemPrivate(
    val channel: Channel,
    val presence: Presence?,
    val selected: Boolean,
    override val mentionCount: Int,
    val isApplicationStreaming: Boolean,
    val muted: Boolean,
    val nameplate: Nameplate? = null,
) : ChannelListItem, WidgetChannelListUnreads.UnreadItem {
    override val type: Int = ChannelListItem.TYPE_CHANNEL_PRIVATE
    override val key: String = "$type${channel.id}"

    /**
     * The private-channel list has no unread jump bars, so the reference reports every DM row
     * as read to [WidgetChannelListUnreads] regardless of its actual state. Mentions still
     * count, which is why only this half of the contract is stubbed.
     */
    override val isUnread: Boolean get() = false
}
