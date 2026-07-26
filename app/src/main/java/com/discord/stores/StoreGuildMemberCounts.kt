package com.discord.stores

import com.discord.api.guild.Guild
import com.discord.api.guildmember.GuildMember
import com.discord.app.AppLog
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

/**
 * The live member count of each guild.
 *
 * `member_count` only arrives with the guild object, so after `READY` the count would freeze.
 * `GUILD_MEMBER_ADD` and `GUILD_MEMBER_REMOVE` therefore adjust it by one — the guild header and
 * the member-list footer read this store rather than `Guild.memberCount`.
 *
 * The increment is deliberately conditional on the guild already being known: an event for a guild
 * that was never hydrated must not create a phantom count of 1.
 */
class StoreGuildMemberCounts(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {
    private val guildMemberCounts = HashMap<Long, Int>()
    private var guildMemberCountsSnapshot: Map<Long, Int> = emptyMap()

    fun getApproximateMemberCount(guildId: Long): Int = guildMemberCountsSnapshot[guildId] ?: 0

    fun observeApproximateMemberCount(guildId: Long): Observable<Int> =
        observationDeck.connectRx(this) { guildMemberCountsSnapshot[guildId] ?: 0 }.distinctUntilChanged()

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        payload.guilds.orEmpty().forEach { guildMemberCounts[it.id] = it.memberCount }
        markChanged()
    }

    @StoreThread
    fun handleGuildCreate(guild: Guild) {
        guildMemberCounts[guild.id] = guild.memberCount
        markChanged()
    }

    @StoreThread
    fun handleGuildDelete(guildId: Long) {
        guildMemberCounts.remove(guildId)
        markChanged()
    }

    @StoreThread
    fun handleGuildMemberAdd(member: GuildMember) {
        val current = guildMemberCounts[member.guildId] ?: return
        guildMemberCounts[member.guildId] = current + 1
        markChanged()
    }

    @StoreThread
    fun handleGuildMemberRemove(guildId: Long) {
        val current = guildMemberCounts[guildId] ?: return
        guildMemberCounts[guildId] = current - 1
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        try {
            guildMemberCountsSnapshot = HashMap(guildMemberCounts)
        } catch (error: OutOfMemoryError) {
            // Recorded before rethrowing so the crash report says how many guilds were held.
            AppLog.recordBreadcrumb(
                "OOM in StoreGuildMemberCounts. size: ${guildMemberCounts.size}",
                "StoreGuildMemberCounts",
            )
            throw error
        }
    }
}
