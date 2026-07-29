package com.discord.widgets.hubs

import com.discord.api.directory.DirectoryEntryGuild
import com.discord.models.guild.Guild

data class HubAddServerState(
    val hubName: String = "",
    val directoryChannelId: Long? = null,
    val selectedIndex: Int = 0,
    val selectableGuilds: List<Guild> = emptyList(),
    val addedGuilds: List<Guild> = emptyList(),
    val addedDirectories: List<DirectoryEntryGuild> = emptyList(),
)
