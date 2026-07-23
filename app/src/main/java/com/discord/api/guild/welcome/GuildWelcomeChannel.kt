package com.discord.api.guild.welcome

data class GuildWelcomeChannel(
    val channelId: Long,
    val description: String,
    val emojiId: Long?,
    val emojiName: String?,
)
