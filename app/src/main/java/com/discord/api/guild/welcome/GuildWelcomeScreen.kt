package com.discord.api.guild.welcome

data class GuildWelcomeScreen(
    val description: String?,
    val welcomeChannels: List<GuildWelcomeChannel>?,
)
