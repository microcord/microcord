package com.discord.stores

import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import rx.Observable

/**
 * How many members hold each role, per guild.
 *
 * The count is not on the role object and is not pushed over the gateway, so the role settings
 * screen fetches it. Because the number drifts constantly in a busy guild there is no attempt to
 * keep it live — a result is reused for [CACHE_TIME_MS] and then refetched.
 *
 * The fetch time is recorded *before* the request so two screens opening at once do not both ask;
 * a failure clears it again so the next attempt is not blocked for two minutes.
 */
class StoreGuildRoleMemberCounts(
    private val dispatcher: Dispatcher,
    private val clock: Clock = ClockFactory.get(),
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {
    private val guildRoleMemberCounts = HashMap<Long, Map<Long, Int>>()
    private val guildRoleMemberCountFetchTimes = HashMap<Long, Long>()
    private var guildRoleMemberCountsSnapshot: Map<Long, Map<Long, Int>> = emptyMap()

    private var api: RestAPIInterface? = null

    fun bindApi(api: RestAPIInterface) {
        this.api = api
    }

    fun getGuildRoleMemberCounts(guildId: Long): Map<Long, Int>? = guildRoleMemberCountsSnapshot[guildId]

    fun observeGuildRoleMemberCounts(guildId: Long): Observable<Map<Long, Int>?> =
        observationDeck.connectRx(this) { guildRoleMemberCountsSnapshot[guildId] }.distinctUntilChanged()

    fun fetchGuildRoleMemberCounts(guildId: Long) = dispatcher.schedule {
        fetchGuildRoleMemberCountsIfNecessary(guildId)
    }

    @StoreThread
    fun handleGuildRemove(guildId: Long) {
        guildRoleMemberCounts.remove(guildId)
        guildRoleMemberCountFetchTimes.remove(guildId)
        markChanged()
    }

    @StoreThread
    fun reset() {
        guildRoleMemberCounts.clear()
        guildRoleMemberCountFetchTimes.clear()
        markChanged()
    }

    @StoreThread
    private fun fetchGuildRoleMemberCountsIfNecessary(guildId: Long) {
        val lastFetch = guildRoleMemberCountFetchTimes[guildId]
        val isFresh = guildRoleMemberCounts[guildId] != null &&
            lastFetch != null &&
            clock.currentTimeMillis() - lastFetch < CACHE_TIME_MS
        if (isFresh) return
        val api = api ?: return
        guildRoleMemberCountFetchTimes[guildId] = clock.currentTimeMillis()
        api.getGuildRoleMemberCounts(guildId)
            .restSubscribeOn()
            .appSubscribe(
                StoreGuildRoleMemberCounts::class.java,
                errorHandler = {
                    dispatcher.schedule { guildRoleMemberCountFetchTimes.remove(guildId) }
                },
            ) { counts ->
                dispatcher.schedule {
                    guildRoleMemberCounts[guildId] = counts
                    markChanged()
                }
            }
    }

    @StoreThread
    override fun snapshotData() {
        guildRoleMemberCountsSnapshot = HashMap(guildRoleMemberCounts)
    }

    private companion object {
        private const val CACHE_TIME_MS = 120_000L
    }
}
