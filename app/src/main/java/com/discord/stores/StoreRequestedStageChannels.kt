package com.discord.stores

import androidx.annotation.MainThread
import com.discord.api.stageinstance.RecommendedStageInstance
import com.discord.api.stageinstance.StageInstance
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable
import rx.Subscription
import java.util.concurrent.TimeUnit

/**
 * Stage instances fetched **by channel id** for channels the client is not otherwise subscribed to.
 *
 * Distinct from [StoreStageInstances], which holds the stage instances that arrive over the gateway
 * for guilds the user is in. This store answers the other question: "there is a stage-invite card
 * in chat pointing at a channel I know nothing about — what is happening in it?". The endpoint
 * (`GET /stage-instances/extra`) returns the richer [RecommendedStageInstance], with the speaker
 * ids and a participant count that a plain [StageInstance] does not carry.
 *
 * **Requests are coalesced.** One chat screen can bind a dozen stage-invite rows in the same frame;
 * [enqueueStageChannelFetch] collects their channel ids and [ENQUEUE_DEBOUNCE_DELAY_MS] later
 * issues a single batched request. Only channels that are unknown or previously errored are
 * enqueued, and [fetchStageChannels] filters again at dispatch time so a channel already loading is
 * never asked for twice.
 *
 * A channel that the response does not mention is recorded as
 * `StageInstanceState(null, FetchStatus.LOADED)` — "asked, and there is no live stage" — which is
 * what lets a row render "this stage has ended" rather than an eternal spinner.
 */
class StoreRequestedStageChannels(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
    private val timer: (Long, TimeUnit) -> Observable<Long> =
        { delay, unit -> Observable.timer(delay, unit) },
) : StoreV2() {

    /**
     * Declaration order is `ERROR, LOADING, LOADED`, read from the `$VALUES` array in the
     * reference's static initialiser rather than the alphabetised order the decompiler prints.
     */
    enum class FetchStatus { ERROR, LOADING, LOADED }

    data class StageInstanceState(
        val stageInstance: RecommendedStageInstance?,
        val status: FetchStatus,
    ) {
        val isError: Boolean get() = status == FetchStatus.ERROR
        val isLoaded: Boolean get() = status == FetchStatus.LOADED
        val isLoading: Boolean get() = status == FetchStatus.LOADING
    }

    private val requestedStageChannels = LinkedHashMap<Long, StageInstanceState>()
    private var requestedStageChannelsSnapshot: Map<Long, StageInstanceState> = emptyMap()

    private val enqueuedChannelFetches = LinkedHashSet<Long>()
    private var enqueuedChannelFetchSubscription: Subscription? = null

    fun getRequestedInstanceStatesByChannel(): Map<Long, StageInstanceState> =
        requestedStageChannelsSnapshot

    /** The plain stage instance inside the recommendation, for callers that only need that. */
    fun getStageInstanceForChannel(channelId: Long): StageInstance? =
        getRequestedInstanceStatesByChannel()[channelId]?.stageInstance?.instance

    fun observeRequestedStageChannels(): Observable<Map<Long, StageInstanceState>> =
        observationDeck.connectRx(this) { getRequestedInstanceStatesByChannel() }.distinctUntilChanged()

    fun observeRequestedStageChannel(channelId: Long): Observable<StageInstanceState?> =
        observeRequestedStageChannels().map { it[channelId] }

    /**
     * Adds [channelId] to the next batch. Called from view binding, so it must not touch store
     * state — it reads the published snapshot only and defers everything else.
     *
     * The reference debounces with a coroutine job on the main dispatcher; this uses an rx timer
     * for the same effect, because `kotlinx.coroutines` is only a transitive dependency here.
     */
    @MainThread
    fun enqueueStageChannelFetch(channelId: Long) {
        val state = requestedStageChannelsSnapshot[channelId]
        if (state != null && !state.isError) return
        enqueuedChannelFetches.add(channelId)
        if (enqueuedChannelFetchSubscription?.isUnsubscribed == false) return
        enqueuedChannelFetchSubscription =
            timer(ENQUEUE_DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS).subscribe {
                val batch = enqueuedChannelFetches.toSet()
                enqueuedChannelFetches.clear()
                if (batch.isNotEmpty()) fetchStageChannels(batch)
            }
    }

    /** Requests every channel in [channelIds] that is not already loading. */
    fun fetchStageChannels(channelIds: Set<Long>) = dispatcher.schedule {
        val toRequest = channelIds
            .filterTo(LinkedHashSet()) { requestedStageChannelsSnapshot[it]?.isLoading != true }
        if (toRequest.isEmpty()) return@schedule
        updateStatus(toRequest, FetchStatus.LOADING)
        val api = restApi() ?: return@schedule updateStatus(toRequest, FetchStatus.ERROR)
        api.getStageInstancesForChannels(toRequest)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreRequestedStageChannels::class.java,
                errorHandler = { dispatcher.schedule { updateStatus(toRequest, FetchStatus.ERROR) } },
            ) { stageInstances ->
                dispatcher.schedule { onLoaded(toRequest, stageInstances) }
            }
    }

    /**
     * Records the returned instances, then marks every *requested but unmentioned* channel as
     * loaded-with-nothing. Both halves matter: without the second, a stage that has ended leaves
     * its row spinning forever.
     */
    @StoreThread
    internal fun onLoaded(channelIds: Set<Long>, stageInstances: List<RecommendedStageInstance>) {
        val remaining = channelIds.toMutableSet()
        stageInstances.forEach { recommended ->
            val channelId = recommended.instance.channelId
            requestedStageChannels[channelId] = StageInstanceState(recommended, FetchStatus.LOADED)
            remaining.remove(channelId)
        }
        remaining.forEach { channelId ->
            requestedStageChannels[channelId] = StageInstanceState(null, FetchStatus.LOADED)
        }
        markChanged()
    }

    /** Keeps whatever instance is already held; only the status changes. */
    @StoreThread
    internal fun updateStatus(channelIds: Set<Long>, status: FetchStatus) {
        channelIds.forEach { channelId ->
            val existing = requestedStageChannels[channelId]?.stageInstance
            requestedStageChannels[channelId] = StageInstanceState(existing, status)
        }
        markChanged()
    }

    @StoreThread
    internal fun reset() {
        requestedStageChannels.clear()
        enqueuedChannelFetches.clear()
        enqueuedChannelFetchSubscription?.unsubscribe()
        enqueuedChannelFetchSubscription = null
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        requestedStageChannelsSnapshot = HashMap(requestedStageChannels)
    }

    companion object {
        /** Long enough to coalesce one frame's worth of row binds, short enough not to be felt. */
        internal const val ENQUEUE_DEBOUNCE_DELAY_MS = 22L
    }
}
