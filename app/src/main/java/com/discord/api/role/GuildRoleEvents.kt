package com.discord.api.role

import com.discord.api.guildhash.GuildHashes

data class GuildRoleCreate(
    val guildId: Long,
    val roleId: Long,
    val role: GuildRole?,
    val guildHashes: GuildHashes?,
)

data class GuildRoleUpdate(
    val guildId: Long,
    val roleId: Long,
    val role: GuildRole?,
    val guildHashes: GuildHashes?,
)

data class GuildRoleDelete(
    val guildId: Long,
    val roleId: Long,
    val guildHashes: GuildHashes?,
) {
    @Transient var version: Long = 0
}
