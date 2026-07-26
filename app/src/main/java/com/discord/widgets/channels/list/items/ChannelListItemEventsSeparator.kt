package com.discord.widgets.channels.list.items

/** Divider drawn above ([isTop] `true`) and below the active-event notice. */
class ChannelListItemEventsSeparator(val isTop: Boolean) : ChannelListItem {
    override val type: Int = ChannelListItem.TYPE_STAGE_EVENTS_SEPARATOR
    override val key: String = "$type$isTop"

    override fun equals(other: Any?): Boolean =
        other is ChannelListItemEventsSeparator && other.isTop == isTop
    override fun hashCode(): Int = isTop.hashCode()
    override fun toString(): String = "ChannelListItemEventsSeparator(isTop=$isTop)"
}
