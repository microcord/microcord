package com.discord.api.emoji

import com.discord.api.guildhash.GuildHashes

/** Payload of `GUILD_EMOJIS_UPDATE`. */
data class GuildEmojisUpdate(
    val guildId: Long,
    val emojis: List<GuildEmoji>,
    val guildHashes: GuildHashes,
)
