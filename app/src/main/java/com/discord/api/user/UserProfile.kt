package com.discord.api.user

import com.discord.api.application.ProfileApplication
import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.api.guildmember.GuildMember

data class UserProfile(
    val connectedAccounts: List<ConnectedAccount> = emptyList(),
    val mutualGuilds: List<GuildReference> = emptyList(),
    val user: User = User(0, ""),
    val premiumSince: String? = null,
    val premiumGuildSince: String? = null,
    val guildMember: GuildMember? = null,
    val application: ProfileApplication? = null,
) {
    data class GuildReference(val id: Long, val nick: String?)
}
