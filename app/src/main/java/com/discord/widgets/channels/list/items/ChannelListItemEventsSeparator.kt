package com.discord.widgets.channels.list.items

/** Divider drawn above ([isTop] `true`) and below the active-event notice. */
class ChannelListItemEventsSeparator(isTop: Boolean) : ChannelListItem {
    override val type: Int = ChannelListItem.TYPE_STAGE_EVENTS_SEPARATOR
    override val key: String = "$type$isTop"
}
