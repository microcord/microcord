package com.discord.api.guild.preview

import com.discord.api.emoji.GuildEmoji
import com.discord.api.guild.GuildFeature

/** The public summary of a guild, shown before the viewer has joined it. */
data class GuildPreview(
    val id: Long,
    val name: String,
    val description: String?,
    val splash: String?,
    val banner: String?,
    val icon: String?,
    val approximatePresenceCount: Int?,
    val approximateMemberCount: Int?,
    val emojis: List<GuildEmoji>,
    val features: List<GuildFeature>,
    val featurableInDirectory: Boolean?,
)
