package com.discord.stores

import com.discord.api.channel.Channel

/**
 * Follows the user across a channel conversion.
 *
 * When a channel is converted (for example a guild text channel turned into a forum), the server
 * creates a *new* channel that carries the old one's id in [Channel.originChannelId] and deletes the
 * old one. Without this store the user would be left staring at a channel that no longer exists, so
 * the new channel is selected automatically — but only when the converted channel is the one
 * currently open, otherwise a conversion in any guild would yank the user out of their channel.
 */
class StoreChannelConversions(
    /** `(guildId, channelId)`; the reference calls `ChannelSelector.selectChannel(0L, id, null, null)`. */
    private val selectChannel: (guildId: Long, channelId: Long) -> Unit,
) : StoreV2() {

    private var selectedChannelId: Long = 0L

    @StoreThread
    fun handleChannelCreateOrUpdate(channel: Channel?) {
        val originChannelId = channel?.originChannelId ?: return
        if (originChannelId == 0L) return
        if (selectedChannelId != originChannelId) return
        selectChannel(0L, channel.id)
    }

    @StoreThread
    fun handleChannelSelected(channelId: Long) {
        selectedChannelId = channelId
    }
}
