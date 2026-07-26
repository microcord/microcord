package com.discord.api.sticker

import com.discord.api.guildhash.GuildHashes

/** Payload of `GUILD_STICKERS_UPDATE`. */
data class GuildStickersUpdate(
    val guildId: Long,
    val stickers: List<Sticker>,
    val guildHashes: GuildHashes,
)
