package com.discord.stores

import com.discord.models.domain.ModelChannelFollowerStats
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.time.Clock
import rx.Observable

/**
 * Follower counts for announcement channels, shown on the "publish message" surfaces.
 *
 * Fetch-on-demand with a one-hour freshness window. The [FetchState] is kept alongside the data so a
 * second request while one is in flight is dropped rather than duplicated, and so a failed fetch can
 * be retried on the next request instead of being cached as "known empty".
 */
class StoreChannelFollowerStats(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val clock: Clock,
) : StoreV2() {

    enum class FetchState { FETCHING, FAILED, SUCCEEDED }

    data class ChannelFollowerStatData(
        val fetchState: FetchState,
        val data: ModelChannelFollowerStats = ModelChannelFollowerStats(0L),
    )

    private val channelFollowerStatsState = HashMap<Long, ChannelFollowerStatData>()
    private var channelFollowerStatsStateSnapshot: Map<Long, ChannelFollowerStatData> = emptyMap()

    /** Bound by `StoreStream` once the REST stack exists. */
    private var restApi: RestAPIInterface? = null

    fun bindApi(api: RestAPIInterface) {
        this.restApi = api
    }

    fun fetchChannelFollowerStats(channelId: Long) = fetchIfNonexistingOrStale(channelId)

    fun observeChannelFollowerStats(channelId: Long): Observable<ModelChannelFollowerStats?> =
        observationDeck.connectRx(this) { channelFollowerStatsStateSnapshot[channelId]?.data }
            .distinctUntilChanged()

    @StoreThread
    override fun snapshotData() {
        channelFollowerStatsStateSnapshot = HashMap(channelFollowerStatsState)
    }

    @StoreThread
    internal fun reset() {
        if (channelFollowerStatsState.isEmpty()) return
        channelFollowerStatsState.clear()
        markChanged()
    }

    private fun fetchIfNonexistingOrStale(channelId: Long) = dispatcher.schedule {
        val current = channelFollowerStatsState[channelId]
        val fresh = isExisting(channelId) && !isStale(channelId)
        if (fresh || current?.fetchState == FetchState.FETCHING) return@schedule
        val api = restApi ?: return@schedule
        handleFetchStart(channelId)
        api.getChannelFollowerStats(channelId)
            .restSubscribeOn()
            .appSubscribe(
                javaClass,
                errorHandler = { dispatcher.schedule { handleFetchFailed(channelId) } },
            ) { response ->
                dispatcher.schedule {
                    handleFetchSuccess(channelId, ModelChannelFollowerStats.fromResponse(response))
                }
            }
    }

    @StoreThread
    private fun handleFetchStart(channelId: Long) {
        channelFollowerStatsState[channelId] = ChannelFollowerStatData(FetchState.FETCHING)
        markChanged()
    }

    @StoreThread
    private fun handleFetchFailed(channelId: Long) {
        channelFollowerStatsState[channelId] = ChannelFollowerStatData(FetchState.FAILED)
        markChanged()
    }

    @StoreThread
    private fun handleFetchSuccess(channelId: Long, stats: ModelChannelFollowerStats) {
        channelFollowerStatsState[channelId] = ChannelFollowerStatData(FetchState.SUCCEEDED, stats)
        markChanged()
    }

    /** A failed entry does not count as existing, so failures are retried on the next request. */
    @StoreThread
    private fun isExisting(channelId: Long): Boolean =
        channelFollowerStatsState[channelId]?.fetchState.let { it != null && it != FetchState.FAILED }

    @StoreThread
    private fun isStale(channelId: Long): Boolean {
        val lastFetched = channelFollowerStatsState[channelId]?.data?.lastFetched ?: return true
        return lastFetched + STALE_TIME_DIFF_MS <= clock.currentTimeMillis()
    }

    companion object {
        private const val STALE_TIME_DIFF_MS = 3_600_000L
    }
}
