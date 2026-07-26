package com.discord.api.guildscheduledevent

import com.discord.api.guildmember.GuildMember
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime

/**
 * The guild-member half of an RSVP row.
 *
 * The server omits the user here because it is already carried by the enclosing
 * [ApiGuildScheduledEventUser], so this type only exists to be recombined with it.
 */
data class ApiGuildScheduledEventUserGuildMember(
    val roles: List<Long>,
    val nick: String?,
    val premiumSince: String?,
    val joinedAt: UtcDateTime?,
    val pending: Boolean,
    val avatar: String?,
) {
    /** Recombines this row with the [user] it was sent alongside into a full [GuildMember]. */
    fun toGuildMember(user: User, guildId: Long): GuildMember = GuildMember(
        guildId = guildId,
        user = user,
        roles = roles,
        nick = nick,
        premiumSince = premiumSince,
        joinedAt = joinedAt,
        pending = pending,
        presence = null,
        userId = user.id,
        avatar = avatar,
    )
}

/** One person who has RSVP'd to a guild scheduled event. */
data class ApiGuildScheduledEventUser(
    val user: User?,
    val member: ApiGuildScheduledEventUserGuildMember?,
    val guildScheduledEventId: Long,
) {
    /** The RSVP as a [GuildMember], or `null` when the server sent neither half. */
    fun toGuildMember(guildId: Long): GuildMember? =
        if (user != null && member != null) member.toGuildMember(user, guildId) else null
}
