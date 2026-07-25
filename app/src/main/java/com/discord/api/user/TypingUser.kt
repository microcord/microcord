package com.discord.api.user

import com.discord.api.guildmember.GuildMember

data class TypingUser(
    val userId: Long,
    val guildId: Long,
    val channelId: Long,
    val member: GuildMember?,
)
