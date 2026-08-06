package com.discord.stores

import com.discord.api.collectibles.CollectibleProduct
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * The collectibles the current user owns, from `GET users/@me/collectibles-purchases`.
 *
 * This is the inventory the profile pickers offer, so it is the only source of what may be equipped.
 * A product the user owns but can no longer use — a Nitro-exclusive one after the subscription
 * lapses — stays in [State.purchases]; deciding whether it is still equippable is the caller's job,
 * since the picker still has to show it.
 *
 * [State] reports fetch progress separately from content so callers can tell "still loading" and
 * "the request failed" apart from "you own nothing", which look identical otherwise.
 */
class StoreCollectibles(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
) : StoreV2() {

    data class State(
        val purchases: Map<Long, CollectibleProduct> = emptyMap(),
        val isFetching: Boolean = false,
        val hasFetched: Boolean = false,
        val failed: Boolean = false,
    ) {
        fun product(skuId: Long?): CollectibleProduct? = skuId?.let(purchases::get)
    }

    private var state = State()
    private var stateSnapshot = State()

    fun getState(): State = stateSnapshot

    fun observeState(): Observable<State> = observationDeck.connectRx(this) { stateSnapshot }

    /**
     * Warms the inventory. Repeat calls are free once it has loaded, so every screen that needs it
     * can ask on open; [force] re-requests anyway, for after a purchase.
     */
    fun fetchPurchases(force: Boolean = false) = dispatcher.schedule {
        if (state.isFetching) return@schedule
        if (state.hasFetched && !state.failed && !force) return@schedule
        state = state.copy(isFetching = true, failed = false)
        markChanged()
        requestPurchases()
    }

    fun invalidate() = dispatcher.schedule {
        state = State()
        markChanged()
    }

    @StoreThread
    private fun requestPurchases() {
        val api = restApi()
        if (api == null) {
            handleFetchError()
            return
        }
        api.getCollectiblesPurchases()
            .restSubscribeOn(false)
            .appSubscribe(
                StoreCollectibles::class.java,
                errorHandler = { dispatcher.schedule { handleFetchError() } },
            ) { purchases -> dispatcher.schedule { handleFetchSuccess(purchases) } }
    }

    @StoreThread
    fun handleFetchSuccess(purchases: List<CollectibleProduct>) {
        state = State(
            purchases = purchases.associateByTo(LinkedHashMap(), CollectibleProduct::skuId),
            isFetching = false,
            hasFetched = true,
            failed = false,
        )
        markChanged()
    }

    @StoreThread
    fun handleFetchError() {
        state = state.copy(isFetching = false, failed = true)
        markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        state = State()
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        stateSnapshot = state.copy(purchases = LinkedHashMap(state.purchases))
    }
}
