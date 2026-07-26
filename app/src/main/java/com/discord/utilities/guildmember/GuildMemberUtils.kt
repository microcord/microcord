@file:JvmName("GuildMemberUtils")

package com.discord.utilities.guildmember

import com.discord.models.member.GuildMember
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory

/**
 * A member has to have been in the guild this long before some guild affordances
 * (notably the invite and verification gates) treat them as settled.
 */
private const val AGE_THRESHOLD_MS = 600_000L

/**
 * Whether this member is still a "lurker": either not loaded at all, still pending
 * membership screening, or never actually joined.
 *
 * Lurkers see a guild through [com.discord.api.permission.Permission.AllowList], not
 * through their computed permissions.
 */
fun GuildMember?.isLurker(): Boolean =
    this == null || pending || joinedAt == null

/** [joinedAt] in millis, or now when the member has not been hydrated yet. */
@JvmOverloads
fun getJoinedAtOrNow(joinedAt: Long?, clock: Clock = ClockFactory.get()): Long =
    joinedAt ?: clock.currentTimeMillis()

/** Whether a member who joined at [joinedAt] has passed the settling threshold. */
@JvmOverloads
fun isGuildMemberOldEnough(joinedAt: Long, clock: Clock = ClockFactory.get()): Boolean =
    clock.currentTimeMillis() - joinedAt > AGE_THRESHOLD_MS
