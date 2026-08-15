package com.discord.widgets.channels.list.items

/** [section] keeps the key unique: rows are diffed by key, so two separators sharing one collide. */
class ChannelListItemEventsSeparator(
    isTop: Boolean,
    section: String = "events",
    /** Drops the gap below, for a separator that a section header already spaces itself from. */
    val tightBelow: Boolean = false,
) : ChannelListItem {
    override val type: Int = ChannelListItem.TYPE_STAGE_EVENTS_SEPARATOR
    override val key: String = "$type$section$isTop"
}
