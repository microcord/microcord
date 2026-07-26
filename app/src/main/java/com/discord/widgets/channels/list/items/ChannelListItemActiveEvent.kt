package com.discord.widgets.channels.list.items

/** The "happening now" notice pinned above the channel list. */
class ChannelListItemActiveEvent(val data: ChannelListItemActiveEventData) : ChannelListItem {
    override val type: Int = ChannelListItem.TYPE_ACTIVE_EVENT
    override val key: String = "$type${data.locationInfo.locationName}"

    override fun equals(other: Any?): Boolean =
        other is ChannelListItemActiveEvent && other.data == data
    override fun hashCode(): Int = data.hashCode()
    override fun toString(): String = "ChannelListItemActiveEvent(data=$data)"
}
