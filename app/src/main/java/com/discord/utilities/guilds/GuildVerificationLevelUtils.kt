package com.discord.utilities.guilds

import com.discord.api.guild.GuildVerificationLevel
import com.discord.api.role.GuildRole
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.utilities.guildmember.getJoinedAtOrNow
import com.discord.utilities.guildmember.isGuildMemberOldEnough
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.user.UserUtils.hasPhone
import com.discord.utilities.user.UserUtils.isAccountOldEnough
import com.discord.utilities.user.UserUtils.isStaff
import java.util.concurrent.TimeUnit
import rx.Observable

/**
 * Which of a guild's verification requirements the current user has *not* satisfied.
 *
 * The result is the single level that is blocking them — `NONE` means nothing is. Only one level
 * can be reported at a time because the guard shows one message, and the levels are checked from
 * strictest to weakest so the user is told about the hardest requirement first.
 *
 * Reference: `com.discord.utilities.guilds.GuildVerificationLevelUtils`.
 */
object GuildVerificationLevelUtils {

    /**
     * Four escape hatches skip verification entirely, and all four are checked before any level is
     * examined:
     *
     * * the guild owner,
     * * anyone holding a role the guild assigned by hand (a bot-managed role does not count — it
     *   is granted automatically and would defeat the gate),
     * * anyone with a phone on their account, which satisfies every level including `HIGHEST`, and
     * * Discord staff.
     */
    private fun computeVerificationLevelTriggered(
        me: MeUser,
        guild: Guild?,
        member: GuildMember?,
        verificationLevel: GuildVerificationLevel,
        joinedAt: Long,
        clock: Clock,
        guildRoles: Map<Long, GuildRole>?,
    ): GuildVerificationLevel {
        val isOwner = guild != null && guild.isOwner(me.id)
        val hasUnmanagedRole = member?.getRoles()?.any { guildRoles?.get(it)?.managed == false } == true

        if (isOwner || hasUnmanagedRole || me.hasPhone || me.isStaff) return GuildVerificationLevel.NONE

        return when {
            verificationLevel == GuildVerificationLevel.HIGHEST -> GuildVerificationLevel.HIGHEST
            verificationLevel == GuildVerificationLevel.HIGH && !isGuildMemberOldEnough(joinedAt, clock) ->
                GuildVerificationLevel.HIGH
            verificationLevel == GuildVerificationLevel.MEDIUM && !me.isAccountOldEnough(clock) ->
                GuildVerificationLevel.MEDIUM
            verificationLevel == GuildVerificationLevel.LOW && !me.isVerified ->
                GuildVerificationLevel.LOW
            else -> GuildVerificationLevel.NONE
        }
    }

    @JvmOverloads
    fun getVerificationLevelTriggered(
        guildId: Long,
        guildStore: StoreGuilds = StoreStream.getGuilds(),
        userStore: StoreUser = StoreStream.getUsers(),
        clock: Clock = ClockFactory.get(),
    ): GuildVerificationLevel {
        val guild = guildStore.getGuild(guildId)
        val me = userStore.getMe()
        return computeVerificationLevelTriggered(
            me = me,
            guild = guild,
            member = guildStore.getMembers()[guildId]?.get(me.id),
            verificationLevel = guild?.verificationLevel ?: GuildVerificationLevel.NONE,
            joinedAt = getJoinedAtOrNow(guildStore.getGuildsJoinedAt()[guildId], clock),
            clock = clock,
            guildRoles = guildStore.getRoles()[guildId],
        )
    }

    /**
     * The computed-member stream is leading-edge throttled at 1.5 s because it re-emits for every
     * presence and role change in the guild, and the verification gate only depends on whether the
     * user holds *any* unmanaged role — a value that essentially never changes while the screen is
     * open.
     */
    @JvmOverloads
    fun observeVerificationLevelTriggered(
        guildId: Long,
        guildStore: StoreGuilds = StoreStream.getGuilds(),
        userStore: StoreUser = StoreStream.getUsers(),
        clock: Clock = ClockFactory.get(),
    ): Observable<GuildVerificationLevel> = Observable.combineLatest(
        guildStore.observeJoinedAt(guildId),
        guildStore.observeGuild(guildId),
        guildStore.observeVerificationLevel(guildId),
        guildStore.observeComputed(guildId).leadingEdgeThrottle(1500L, TimeUnit.MILLISECONDS),
        userStore.observeMe(true),
        guildStore.observeRoles(guildId),
    ) { joinedAt, guild, verificationLevel, members, me, guildRoles ->
        computeVerificationLevelTriggered(
            me = me,
            guild = guild,
            member = members[me.id],
            verificationLevel = verificationLevel,
            joinedAt = joinedAt,
            clock = clock,
            guildRoles = guildRoles,
        )
    }.distinctUntilChanged()
}
