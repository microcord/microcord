package com.discord.api.guildmember

import com.discord.api.presence.Presence
import com.discord.api.user.User

data class GuildMemberRemove(val guildId: Long, val user: User)

data class GuildMembersChunk(
    val guildId: Long,
    val members: List<GuildMember>?,
    val presences: List<Presence>?,
    val notFound: List<Long>?,
)
