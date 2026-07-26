package com.discord.widgets.channels.list.items

import com.discord.api.channel.Channel

/** Shared surface of the two vocal rows so the adapter can render a connected-user count. */
interface ChannelListVocalItem {
    val channel: Channel
    val numUsersConnected: Int
}
