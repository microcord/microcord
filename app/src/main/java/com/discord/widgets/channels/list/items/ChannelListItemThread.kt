package com.discord.widgets.channels.list.items

import com.discord.api.channel.Channel
import com.discord.widgets.channels.list.WidgetChannelListModel
import com.discord.widgets.channels.list.WidgetChannelListUnreads

data class ChannelListItemThread(
    val channel: Channel,
    val selected: Boolean,
    override val mentionCount: Int,
    override val isUnread: Boolean,
    val isLocked: Boolean,
    val muted: Boolean,
) : ChannelListItem, WidgetChannelListUnreads.UnreadItem {

    override val type: Int = ChannelListItem.TYPE_THREAD

    /**
     * Which segment of the spine connector this row draws. Assigned by the model builder after
     * a parent channel's threads have been collected, because it depends on the row's position
     * within that group — which is why it is the one mutable field on any list item, and why
     * [key] is computed rather than stored.
     */
    var spineType: WidgetChannelListModel.ThreadSpineType =
        WidgetChannelListModel.ThreadSpineType.Single

    override val key: String get() = "$type${channel.id}$spineType$muted"
}
