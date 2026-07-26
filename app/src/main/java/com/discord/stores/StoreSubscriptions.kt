package com.discord.stores

import com.discord.models.domain.ModelSubscription
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * The current user's billing subscriptions.
 *
 * `USER_SUBSCRIPTIONS_UPDATE` carries no payload — the socket substitutes `Unit` — so the only
 * possible reaction is to re-fetch, which is exactly what [handleUserSubscriptionsUpdate] does.
 *
 * [SubscriptionsState.Unfetched] is distinct from `Loaded(emptyList())`: the first means "we have
 * never asked", the second "the user has no subscriptions". Screens render a spinner for the
 * former and an upsell for the latter, so collapsing them would be a visible bug.
 */
class StoreSubscriptions(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val dispatcher: Dispatcher,
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
) : StoreV2() {

    sealed class SubscriptionsState {
        object Unfetched : SubscriptionsState()
        object Loading : SubscriptionsState()
        object Failure : SubscriptionsState()

        data class Loaded(val subscriptions: List<ModelSubscription>) : SubscriptionsState() {
            /** The Nitro subscription, if the account has one. */
            val premiumSubscription: ModelSubscription?
                get() = subscriptions.firstOrNull { it.type == ModelSubscription.Type.PREMIUM }
        }
    }

    private var subscriptionsState: SubscriptionsState = SubscriptionsState.Unfetched
    private var subscriptionsStateSnapshot: SubscriptionsState = SubscriptionsState.Unfetched

    fun getSubscriptions(): SubscriptionsState = subscriptionsStateSnapshot

    fun hasFetchedSubscriptions(): Boolean = subscriptionsStateSnapshot is SubscriptionsState.Loaded

    fun isFetchingSubscriptions(): Boolean = subscriptionsStateSnapshot is SubscriptionsState.Loading

    fun observeSubscriptions(): Observable<SubscriptionsState> =
        observationDeck.connectRx(this) { getSubscriptions() }.distinctUntilChanged()

    /**
     * De-duplicates on [SubscriptionsState.Loading] only. A second call after a success or failure
     * re-requests, which is what makes the gateway-triggered refresh work.
     */
    fun fetchSubscriptions() = dispatcher.schedule {
        if (subscriptionsState is SubscriptionsState.Loading) return@schedule
        handleSubscriptionsFetchStart()
        val api = restApi()
        if (api == null) {
            handleSubscriptionsFetchFailure()
            return@schedule
        }
        api.getSubscriptions()
            .restSubscribeOn(false)
            .appSubscribe(
                StoreSubscriptions::class.java,
                errorHandler = { dispatcher.schedule { handleSubscriptionsFetchFailure() } },
            ) { subscriptions ->
                dispatcher.schedule { handleSubscriptionsFetchSuccess(subscriptions) }
            }
    }

    /** Handler for `USER_SUBSCRIPTIONS_UPDATE`, which arrives with no `d` payload. */
    @StoreThread
    fun handleUserSubscriptionsUpdate() = fetchSubscriptions()

    @StoreThread
    internal fun handleSubscriptionsFetchStart() {
        subscriptionsState = SubscriptionsState.Loading
        markChanged()
    }

    @StoreThread
    internal fun handleSubscriptionsFetchSuccess(subscriptions: List<ModelSubscription>) {
        subscriptionsState = SubscriptionsState.Loaded(subscriptions)
        markChanged()
    }

    @StoreThread
    internal fun handleSubscriptionsFetchFailure() {
        subscriptionsState = SubscriptionsState.Failure
        markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        subscriptionsState = SubscriptionsState.Unfetched
        markChanged()
    }

    /**
     * The list is copied into the snapshot so a consumer iterating it cannot observe a later
     * mutation on the store thread.
     */
    @StoreThread
    override fun snapshotData() {
        val state = subscriptionsState
        subscriptionsStateSnapshot = when (state) {
            is SubscriptionsState.Loaded -> state.copy(subscriptions = ArrayList(state.subscriptions))
            SubscriptionsState.Failure,
            SubscriptionsState.Loading,
            SubscriptionsState.Unfetched,
            -> state
        }
    }
}
