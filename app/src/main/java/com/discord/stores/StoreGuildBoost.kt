package com.discord.stores

import com.discord.models.domain.ModelGuildBoostSlot
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * The current user's Nitro boost slots.
 *
 * A slot is the *capacity to boost*, not the boost itself: a Nitro subscription grants two slots,
 * each of which may be unused, applied to a guild ([ModelGuildBoostSlot.premiumGuildSubscription]),
 * or cooling down after being moved. The whole set is a single [State], because "loading" and
 * "failed" are properties of the one request that fetches all of them.
 *
 * [getGuildBoostsState] / [observeGuildBoostState] optionally filter to one guild. The filter keeps
 * the [State.Loaded] wrapper rather than returning a bare map, so a guild-boost screen can show its
 * own loading and error states without a second source.
 */
class StoreGuildBoost(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
) : StoreV2() {

    sealed class State {
        object Loading : State()
        object Failure : State()

        data class Loaded(val boostSlotMap: Map<Long, ModelGuildBoostSlot>) : State() {
            /** Keeps only the slots currently applied to [guildId]; `null` means "no filter". */
            fun filterByGuildId(guildId: Long?): Loaded {
                if (guildId == null) return this
                return Loaded(
                    boostSlotMap.filterValues { slot ->
                        slot.premiumGuildSubscription?.guildId == guildId
                    },
                )
            }
        }
    }

    /** The store opens on [State.Loading], not on an empty map — nothing has been asked for yet. */
    private var state: State = State.Loading
    private var stateSnapshot: State = State.Loading

    fun getState(): State = stateSnapshot

    @JvmOverloads
    fun getGuildBoostsState(guildId: Long? = null): State =
        getState().let { if (it is State.Loaded) it.filterByGuildId(guildId) else it }

    @JvmOverloads
    fun observeGuildBoostState(guildId: Long? = null): Observable<State> =
        observationDeck.connectRx(this) { getGuildBoostsState(guildId) }

    fun fetchUserGuildBoostState() {
        dispatcher.schedule { handleFetchingState() }
        val api = restApi() ?: return dispatcher.schedule { handleFetchError() }
        api.getSubscriptionSlots()
            .restSubscribeOn(false)
            .appSubscribe(
                StoreGuildBoost::class.java,
                errorHandler = { dispatcher.schedule { handleFetchError() } },
            ) { slots ->
                dispatcher.schedule { handleFetchStateSuccess(slots) }
            }
    }

    @StoreThread
    fun handleFetchingState() {
        state = State.Loading
        markChanged()
    }

    @StoreThread
    fun handleFetchError() {
        state = State.Failure
        markChanged()
    }

    @StoreThread
    fun handleFetchStateSuccess(guildBoostSlots: List<ModelGuildBoostSlot>) {
        state = State.Loaded(guildBoostSlots.associateBy(ModelGuildBoostSlot::id))
        markChanged()
    }

    /**
     * Applies a single slot returned by an apply/cancel call without re-fetching the whole set.
     * A no-op unless the set is already loaded — there is nothing to merge into otherwise.
     */
    fun updateGuildBoostSlot(newSlot: ModelGuildBoostSlot) = dispatcher.schedule {
        val current = state
        if (current !is State.Loaded) return@schedule
        state = State.Loaded(current.boostSlotMap + (newSlot.id to newSlot))
        markChanged()
    }

    @StoreThread
    internal fun reset() {
        state = State.Loading
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        stateSnapshot = when (val current = state) {
            is State.Loading, is State.Failure -> current
            is State.Loaded -> State.Loaded(current.boostSlotMap.toMap())
        }
    }
}
