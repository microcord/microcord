package com.discord.stores

import com.discord.models.domain.ModelVoiceRegion
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * The voice regions a guild may pin its voice channels to.
 *
 * The list is per guild rather than global — it includes any VIP regions the guild is entitled to
 * — and it only matters inside the voice-channel region picker, so it is fetched lazily and never
 * persisted.
 *
 * Only [State.Loaded] reaches the snapshot: callers see either regions or nothing, and never have
 * to distinguish "still loading" from "failed" when they just want a list.
 */
class StoreGuildVoiceRegions(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    sealed class State {
        object Fetching : State()
        object Failure : State()
        data class Loaded(val data: List<ModelVoiceRegion>?) : State()
    }

    private val guildVoiceRegionsState = HashMap<Long, State>()
    private var guildVoiceRegionsSnapshot: Map<Long, List<ModelVoiceRegion>> = emptyMap()

    private var api: RestAPIInterface? = null

    fun bindApi(api: RestAPIInterface) {
        this.api = api
    }

    fun getGuildVoiceRegions(guildId: Long): List<ModelVoiceRegion> =
        guildVoiceRegionsSnapshot[guildId].orEmpty()

    fun observeGuildVoiceRegions(guildId: Long): Observable<List<ModelVoiceRegion>> =
        observationDeck.connectRx(this) { getGuildVoiceRegions(guildId) }.distinctUntilChanged()

    fun fetchIfNonexisting(guildId: Long) = dispatcher.schedule {
        val state = guildVoiceRegionsState[guildId]
        if (state is State.Loaded || state is State.Fetching) return@schedule
        val api = api ?: return@schedule
        handleGuildVoiceRegionsFetchStart(guildId)
        api.getGuildVoiceRegions(guildId)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreGuildVoiceRegions::class.java,
                errorHandler = { dispatcher.schedule { handleGuildVoiceRegionsFetchFailed(guildId) } },
            ) { regions -> dispatcher.schedule { handleGuildVoiceRegions(guildId, regions) } }
    }

    @StoreThread
    internal fun handleGuildVoiceRegions(guildId: Long, voiceRegions: List<ModelVoiceRegion>) {
        guildVoiceRegionsState[guildId] = State.Loaded(voiceRegions)
        markChanged()
    }

    @StoreThread
    internal fun handleGuildVoiceRegionsFetchStart(guildId: Long) {
        guildVoiceRegionsState[guildId] = State.Fetching
        markChanged()
    }

    @StoreThread
    internal fun handleGuildVoiceRegionsFetchFailed(guildId: Long) {
        guildVoiceRegionsState[guildId] = State.Failure
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        guildVoiceRegionsSnapshot = guildVoiceRegionsState
            .filterValues { it is State.Loaded }
            .mapValues { (_, state) -> (state as State.Loaded).data.orEmpty() }
    }
}
