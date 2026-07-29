package com.discord.widgets.channels.list.items

/** The "happening now" notice pinned above the channel list. */
class ChannelListItemActiveEvent(val data: ChannelListItemActiveEventData) : ChannelListItem {
    override val type: Int = ChannelListItem.TYPE_ACTIVE_EVENT
    override val key: String = "$type${data.locationInfo.locationName}"
}
