package com.discord.stores

import com.discord.models.domain.ModelEntitlement
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * What the current user owns, split into two maps that answer two different questions.
 *
 * - [State.Loaded.ownedEntitlements] is keyed by **application id** and answers "does this user
 *   already own the thing this store page is selling?".
 * - [State.Loaded.giftableEntitlements] is keyed by **SKU id** and answers "how many unclaimed
 *   copies of this SKU can the user still gift?" — that is why it is a list per key rather than a
 *   single entitlement.
 *
 * Both fetches share one [State], so starting either one puts the store back into
 * [State.Loading] and the last one to finish wins the visible state. That is the reference
 * behaviour and it matters because the gift sheet issues both.
 */
class StoreEntitlements(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
) : StoreV2() {

    sealed class State {
        /** Deep-copies the mutable collections a [Loaded] holds; the other states are immutable. */
        open fun deepCopy(): State = this

        object Loading : State()
        object Failure : State()

        data class Loaded(
            val giftableEntitlements: Map<Long, List<ModelEntitlement>>,
            val ownedEntitlements: Map<Long, List<ModelEntitlement>>,
        ) : State() {
            override fun deepCopy(): Loaded =
                copy(HashMap(giftableEntitlements), HashMap(ownedEntitlements))
        }
    }

    private val entitlementMap = LinkedHashMap<Long, List<ModelEntitlement>>()
    private var giftEntitlementMap: Map<Long, List<ModelEntitlement>> = emptyMap()
    private var state: State = State.Loading
    private var stateSnapshot: State = State.Loading

    fun getEntitlementState(): State = stateSnapshot

    fun observeEntitlementState(): Observable<State> =
        observationDeck.connectRx(this) { stateSnapshot }

    /** `GET users/@me/applications/{id}/entitlements`, excluding consumed ones. */
    fun fetchMyEntitlementsForApplication(applicationId: Long) {
        dispatcher.schedule { handleFetchingState() }
        val api = restApi()
        if (api == null) {
            dispatcher.schedule { handleFetchError() }
            return
        }
        api.getMyEntitlements(applicationId)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreEntitlements::class.java,
                errorHandler = { dispatcher.schedule { handleFetchError() } },
            ) { entitlements ->
                dispatcher.schedule { handleFetchEntitlementsSuccess(applicationId, entitlements) }
            }
    }

    /** `GET users/@me/entitlements/gifts` — the unclaimed gift copies the user still holds. */
    fun fetchMyGiftEntitlements() {
        dispatcher.schedule { handleFetchingState() }
        val api = restApi()
        if (api == null) {
            dispatcher.schedule { handleFetchError() }
            return
        }
        api.getGifts()
            .restSubscribeOn(false)
            .appSubscribe(
                StoreEntitlements::class.java,
                errorHandler = { dispatcher.schedule { handleFetchError() } },
            ) { entitlements -> dispatcher.schedule { handleFetchGiftsSuccess(entitlements) } }
    }

    @StoreThread
    fun handleFetchingState() {
        state = State.Loading
        markChanged()
    }

    @StoreThread
    fun handleFetchEntitlementsSuccess(applicationId: Long, entitlements: List<ModelEntitlement>) {
        entitlementMap[applicationId] = entitlements
        state = State.Loaded(giftEntitlementMap, entitlementMap)
        markChanged()
    }

    @StoreThread
    fun handleFetchGiftsSuccess(giftEntitlements: List<ModelEntitlement>) {
        giftEntitlementMap = giftEntitlements.groupBy(ModelEntitlement::skuId)
        state = State.Loaded(giftEntitlementMap, entitlementMap)
        markChanged()
    }

    @StoreThread
    fun handleFetchError() {
        state = State.Failure
        markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        entitlementMap.clear()
        giftEntitlementMap = emptyMap()
        state = State.Loading
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        stateSnapshot = state.deepCopy()
    }
}
