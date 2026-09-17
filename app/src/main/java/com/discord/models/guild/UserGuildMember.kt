package com.discord.models.guild

import com.discord.models.member.GuildMember
import com.discord.models.user.User

data class UserGuildMember(
    val user: User,
    val guildMember: GuildMember? = null,
) {
    val nickname: String? = guildMember?.identityNick()

    fun getNickOrUserName() = GuildMember.getNickOrUsername(guildMember, user)
}
