package com.discord.widgets.hubs

import com.discord.api.channel.Channel
import com.discord.api.channel.isHQDirectoryChannel
import com.discord.api.directory.DirectoryEntryGuild
import com.discord.models.hubs.DirectoryEntryCategory
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.RestCallState

data class HubDescriptionState(
    val channel: Channel? = null,
    val primaryCategoryId: Int? = null,
    val guildName: String = "",
    val addServerAsync: RestCallState<DirectoryEntryGuild> = Default,
) {
    val selectedCategory: DirectoryEntryCategory?
        get() = primaryCategoryId?.let {
            DirectoryEntryCategory.findByKey(it, channel?.isHQDirectoryChannel() == true)
        }
}
