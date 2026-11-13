package com.discord.api.emoji

import com.discord.utilities.persister.CachedFormat

@CachedFormat
data class GuildEmoji(
    val id: Long,
    val name: String,
    val roles: List<Long>?,
    val requireColons: Boolean,
    val managed: Boolean,
    val animated: Boolean,
    val available: Boolean?,
) {
    @Transient var version: Long = 0
}
