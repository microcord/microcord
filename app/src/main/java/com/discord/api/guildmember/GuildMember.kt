package com.discord.api.guildmember

import com.discord.api.presence.Presence
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime

data class GuildMember(
    val guildId: Long,
    val user: User,
    val roles: List<Long>,
    val nick: String?,
    val premiumSince: String?,
    val joinedAt: UtcDateTime?,
    val pending: Boolean,
    val presence: Presence?,
    val userId: Long?,
    val avatar: String?,
    val bio: String? = null,
    val banner: String? = null,
    val communicationDisabledUntil: UtcDateTime? = null,
)
