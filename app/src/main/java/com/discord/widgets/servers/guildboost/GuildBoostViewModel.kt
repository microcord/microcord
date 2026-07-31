package com.discord.widgets.servers.guildboost

import androidx.annotation.MainThread
import androidx.annotation.StringRes
import com.android.billingclient.api.Purchase
import com.discord.R
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelGuildBoostSlot
import com.discord.models.domain.ModelSubscription
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.stores.StoreGooglePlayPurchases
import com.discord.stores.StoreGuildBoost
import com.discord.stores.StoreStream
import com.discord.stores.StoreSubscriptions
import com.discord.utilities.billing.GooglePlayBillingManager
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import rx.Observable
import rx.functions.Func5
import rx.subjects.PublishSubject

class GuildBoostViewModel(
    private val guildId: Long,
    private val storeGuildBoost: StoreGuildBoost = StoreStream.getGuildBoost(),
    private val storeSubscriptions: StoreSubscriptions = StoreStream.getSubscriptions(),
    private val clock: Clock = ClockFactory.get(),
    storeObservable: Observable<StoreState> = observeStores(guildId),
) : AppViewModel<GuildBoostViewModel.ViewState>(ViewState.Loading) {
    private val eventSubject = PublishSubject.create<Event>()

    init {
        fetchData()
        storeObservable.computationLatest().ui(this)
            .appSubscribe(GuildBoostViewModel::class.java, onNext = ::handleStoreState)
    }

    fun observeEvents(): Observable<Event> = eventSubject

    @MainThread
    fun retryClicked() = fetchData()

    @MainThread
    fun subscribeClicked(section: String) {
        val loaded = viewState as? ViewState.Loaded ?: return
        val availableSlot = loaded.boostSlotMap.values.firstOrNull {
            it.cooldownExpiresAtTimestamp < clock.currentTimeMillis() && it.premiumGuildSubscription == null
        }
        if (availableSlot != null) {
            eventSubject.onNext(Event.LaunchSubscriptionConfirmation(loaded.guild.id, availableSlot.id))
            return
        }

        val subscription = loaded.premiumSubscription
        when {
            subscription != null && !subscription.isGoogleSubscription() ->
                eventSubject.onNext(Event.ShowDesktopAlertDialog)
            subscription?.renewalMutations != null -> eventSubject.onNext(
                Event.ShowBlockedPlanSwitchAlertDialog(
                    R.string.premium_guild_subscription_out_of_slots_title,
                    R.string.premium_guild_subscription_out_of_slots_pending_plan_change,
                ),
            )
            subscription?.status == ModelSubscription.Status.CANCELED -> eventSubject.onNext(
                Event.ShowBlockedPlanSwitchAlertDialog(
                    R.string.premium_guild_subscription_out_of_slots_title,
                    R.string.premium_guild_subscription_out_of_slots_canceled_subscription,
                ),
            )
            subscription?.status == ModelSubscription.Status.ACCOUNT_HOLD -> eventSubject.onNext(
                Event.ShowBlockedPlanSwitchAlertDialog(
                    R.string.premium_guild_subscription_out_of_slots_account_hold_title,
                    R.string.premium_guild_subscription_out_of_slots_account_hold,
                ),
            )
            loaded.purchases.any { !it.isAcknowledged } -> eventSubject.onNext(Event.UnacknowledgedPurchase)
            else -> eventSubject.onNext(
                Event.LaunchPurchaseSubscription(section, loaded.guild.id, subscription?.paymentGatewayPlanId),
            )
        }
    }

    internal fun handleStoreState(storeState: StoreState) {
        val state = when {
            storeState.guildBoostState is StoreGuildBoost.State.Loading ||
                storeState.subscriptionState is StoreSubscriptions.SubscriptionsState.Loading ||
                storeState.purchasesState is StoreGooglePlayPurchases.State.Uninitialized -> ViewState.Loading
            storeState.guildBoostState is StoreGuildBoost.State.Loaded &&
                storeState.subscriptionState is StoreSubscriptions.SubscriptionsState.Loaded &&
                storeState.purchasesState is StoreGooglePlayPurchases.State.Loaded -> {
                val guild = storeState.guild ?: return
                ViewState.Loaded(
                    storeState.guildBoostState.boostSlotMap,
                    storeState.subscriptionState.premiumSubscription,
                    storeState.purchasesState.subscriptionPurchases,
                    storeState.meUser,
                    guild,
                )
            }
            else -> ViewState.Failure
        }
        updateViewState(state)
    }

    private fun fetchData() {
        storeGuildBoost.fetchUserGuildBoostState()
        storeSubscriptions.fetchSubscriptions()
        GooglePlayBillingManager.queryPurchases()
    }

    data class StoreState(
        val guildBoostState: StoreGuildBoost.State,
        val subscriptionState: StoreSubscriptions.SubscriptionsState,
        val meUser: MeUser,
        val guild: Guild?,
        val purchasesState: StoreGooglePlayPurchases.State,
    )

    sealed class ViewState {
        object Loading : ViewState()
        object Failure : ViewState()
        data class Loaded(
            val boostSlotMap: Map<Long, ModelGuildBoostSlot>,
            val premiumSubscription: ModelSubscription?,
            val purchases: List<Purchase>,
            val meUser: MeUser,
            val guild: Guild,
        ) : ViewState()
    }

    sealed class Event {
        data class LaunchPurchaseSubscription(
            val section: String,
            val guildId: Long,
            val oldSkuName: String? = null,
        ) : Event()
        data class LaunchSubscriptionConfirmation(val guildId: Long, val slotId: Long) : Event()
        data class ShowBlockedPlanSwitchAlertDialog(
            @StringRes val headerStringRes: Int,
            @StringRes val bodyStringRes: Int,
        ) : Event()
        object ShowDesktopAlertDialog : Event()
        object UnacknowledgedPurchase : Event()
    }

    companion object {
        internal fun observeStores(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuildBoost().observeGuildBoostState(),
            StoreStream.getSubscriptions().observeSubscriptions(),
            StoreStream.getUsers().observeMe(),
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getGooglePlayPurchases().observeState(),
            Func5<
                StoreGuildBoost.State,
                StoreSubscriptions.SubscriptionsState,
                MeUser,
                Guild?,
                StoreGooglePlayPurchases.State,
                StoreState,
            > { boosts, subscriptions, me, guild, purchases ->
                StoreState(boosts, subscriptions, me, guild, purchases)
            },
        )
    }
}
