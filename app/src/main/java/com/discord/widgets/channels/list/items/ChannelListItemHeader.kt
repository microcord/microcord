package com.discord.widgets.channels.list.items

import androidx.annotation.StringRes

/**
 * Section heading in the private-channel list ("Direct Messages") and in hub channel lists.
 * [ableToManageChannel] shows the trailing add button.
 */
data class ChannelListItemHeader(
    val id: Long,
    @StringRes val textResId: Int,
    val ableToManageChannel: Boolean,
    val selectedGuildId: Long,
) : ChannelListItem {
    override val type: Int = ChannelListItem.TYPE_HEADER
    override val key: String = "$type$id$textResId"
}
