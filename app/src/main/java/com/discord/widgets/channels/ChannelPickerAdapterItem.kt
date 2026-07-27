package com.discord.widgets.channels

import com.discord.api.channel.Channel
import com.discord.utilities.recycler.DiffKeyProvider

sealed class ChannelPickerAdapterItem : DiffKeyProvider {
    data class ChannelItem(
        val channel: Channel,
        val isSelected: Boolean,
    ) : ChannelPickerAdapterItem() {
        override val key: String = channel.id.toString()
    }

    object CreateChannelItem : ChannelPickerAdapterItem() {
        override val key: String = "channel-picker-create-channel-item"
    }
}
