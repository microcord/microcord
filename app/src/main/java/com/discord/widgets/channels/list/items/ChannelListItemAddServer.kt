package com.discord.widgets.channels.list.items

/** The hub "Add a server" row, rendered with the directory row layout. */
class ChannelListItemAddServer : ChannelListItem {
    override val type: Int = ChannelListItem.TYPE_ADD_SERVER

    override fun equals(other: Any?): Boolean = other is ChannelListItemAddServer
    override fun hashCode(): Int = type
    override fun toString(): String = "ChannelListItemAddServer"
}
