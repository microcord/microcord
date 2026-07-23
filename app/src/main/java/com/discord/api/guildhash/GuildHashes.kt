package com.discord.api.guildhash

data class GuildHash(val hash: String?, val omitted: Boolean)

data class GuildHashes(
    val metadata: GuildHash?,
    val channels: GuildHash?,
    val roles: GuildHash?,
    val version: Int,
)
