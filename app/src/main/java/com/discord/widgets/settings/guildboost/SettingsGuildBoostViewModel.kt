package com.discord.widgets.settings.guildboost

import androidx.annotation.MainThread
import com.discord.R
import com.discord.api.premium.PremiumTier
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelGuildBoostSlot
import com.discord.models.domain.ModelSubscription
import com.discord.models.guild.Guild
import com.discord.stores.StoreGuildBoost
import com.discord.stores.StoreStream
import com.discord.stores.StoreSubscriptions
import com.discord.utilities.rx.ui
import rx.Observable

class SettingsGuildBoostViewModel(
    private val storeGuildBoost: StoreGuildBoost = StoreStream.getGuildBoost(),
    private val storeSubscriptions: StoreSubscriptions = StoreStream.getSubscriptions(),
    storeObservable: Observable<StoreState> = observeStores(),
) : AppViewModel<SettingsGuildBoostViewModel.ViewState>(ViewState.Loading) {
    data class StoreState(
        val guildBoostState: StoreGuildBoost.State,
        val subscriptionState: StoreSubscriptions.SubscriptionsState,
        val guilds: Map<Long, Guild>,
        val userPremiumTier: PremiumTier,
    )

    sealed class PendingAction {
        data class Cancel(val slotId: Long) : PendingAction()
        data class Uncancel(val slotId: Long) : PendingAction()
        data class Subscribe(val slotId: Long, val targetGuildId: Long? = null) : PendingAction()
        data class Transfer(val slot: ModelGuildBoostSlot, val previousGuildId: Long, val targetGuildId: Long? = null) : PendingAction()
    }

    sealed class ViewState {
        data object Loading : ViewState()
        data object Failure : ViewState()
        data class Loaded(
            val canCancelBoosts: Boolean,
            val canUncancelBoosts: Boolean,
            val guildBoostItems: List<WidgetSettingsGuildBoostSubscriptionAdapter.Item>,
            val sampleGuildItems: List<SettingsGuildBoostSampleGuildAdapter.Item>,
            val pendingAction: PendingAction?,
            val userPremiumTier: PremiumTier,
        ) : ViewState()
    }

    init {
        storeObservable.ui(this).subscribe(::handleStoreState)
        fetchData()
    }

    private fun fetchData() {
        storeGuildBoost.fetchUserGuildBoostState()
        storeSubscriptions.fetchSubscriptions()
    }

    @MainThread
    internal fun handleStoreState(state: StoreState) {
        val boostState = state.guildBoostState
        val subscriptionState = state.subscriptionState
        if (boostState is StoreGuildBoost.State.Failure || subscriptionState is StoreSubscriptions.SubscriptionsState.Failure) {
            updateViewState(ViewState.Failure)
            return
        }
        if (boostState !is StoreGuildBoost.State.Loaded || subscriptionState !is StoreSubscriptions.SubscriptionsState.Loaded) {
            updateViewState(ViewState.Loading)
            return
        }
        val premiumSubscription = subscriptionState.premiumSubscription
        val currentPending = (viewState as? ViewState.Loaded)?.pendingAction
        val items = createGuildBoostItems(boostState, state.guilds, premiumSubscription)
        val boostedGuildIds = boostState.boostSlotMap.values.mapNotNull { it.premiumGuildSubscription?.guildId }.toSet()
        val samples = state.guilds.values.filterNot { it.id in boostedGuildIds }.take(NUM_SAMPLE_GUILDS)
            .map(SettingsGuildBoostSampleGuildAdapter::Item)
        val active = premiumSubscription?.status == ModelSubscription.Status.ACTIVE
        updateViewState(ViewState.Loaded(active, !active, items, samples, currentPending, state.userPremiumTier))
    }

    internal fun createGuildBoostItems(
        state: StoreGuildBoost.State.Loaded,
        guilds: Map<Long, Guild>,
        subscription: ModelSubscription?,
    ): List<WidgetSettingsGuildBoostSubscriptionAdapter.Item> = buildList {
        val byGuild = state.boostSlotMap.values.groupBy { it.premiumGuildSubscription?.guildId }
        byGuild[null].orEmpty().forEach { add(WidgetSettingsGuildBoostSubscriptionAdapter.Item.GuildBoostItem(it, subscription?.currentPeriodEnd)) }
        val active = byGuild.filterKeys { it != null }
        if (active.isNotEmpty()) add(WidgetSettingsGuildBoostSubscriptionAdapter.Item.HeaderItem(R.string.premium_guild_subscription_active_title))
        active.forEach { (guildId, slots) ->
            guilds[guildId]?.let { guild ->
                add(WidgetSettingsGuildBoostSubscriptionAdapter.Item.GuildItem(guild, slots.size))
                slots.forEach { add(WidgetSettingsGuildBoostSubscriptionAdapter.Item.GuildBoostItem(it, subscription?.currentPeriodEnd)) }
            }
        }
    }

    @MainThread fun subscribeClicked(slotId: Long) = updatePending(PendingAction.Subscribe(slotId))
    @MainThread fun transferClicked(slot: ModelGuildBoostSlot, previousGuildId: Long) = updatePending(PendingAction.Transfer(slot, previousGuildId))
    @MainThread fun cancelClicked(slotId: Long, cancel: Boolean) = updatePending(if (cancel) PendingAction.Cancel(slotId) else PendingAction.Uncancel(slotId))
    @MainThread fun consumePendingAction() = updatePending(null)
    @MainThread fun retryClicked() = fetchData()

    @MainThread
    fun handleGuildSearchCallback(guildId: Long) {
        val loaded = viewState as? ViewState.Loaded ?: return
        val action = when (val pending = loaded.pendingAction) {
            is PendingAction.Subscribe -> pending.copy(targetGuildId = guildId)
            is PendingAction.Transfer -> pending.copy(targetGuildId = guildId)
            else -> pending
        }
        updatePending(action)
    }

    private fun updatePending(action: PendingAction?) {
        val loaded = viewState as? ViewState.Loaded ?: return
        updateViewState(loaded.copy(pendingAction = action))
    }

    companion object {
        private const val NUM_SAMPLE_GUILDS = 4

        private fun observeStores(): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuildBoost().observeGuildBoostState(),
            StoreStream.getSubscriptions().observeSubscriptions(),
            StoreStream.getGuildsSorted().observeOrderedGuilds(),
            StoreStream.getUsers().observeMe().map { it.premiumTier }.distinctUntilChanged(),
        ) { boost, subscriptions, guilds, premiumTier -> StoreState(boost, subscriptions, guilds, premiumTier) }
    }
}
