package com.discord.widgets.guild_role_subscriptions.entrypoint

import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.stores.StoreConnectivity
import com.discord.stores.StoreGuildRoleSubscriptions
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import com.discord.stores.StoreSubscriptions
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.widgets.guild_role_subscriptions.GuildRoleSubscriptionsFeatureFlag
import com.discord.widgets.guild_role_subscriptions.hasUserActiveSubscriptionFor
import rx.Observable
import rx.Subscription

/**
 * Backs the "this server sells memberships" landing screen.
 *
 * The screen exists to explain that purchases happen on the web, so its only real decision is
 * whether the guild is one that *has* memberships. Guarding [ViewState.Invalid] on
 * [StoreState.isConnected] matters: while the client is still connecting the guild is legitimately
 * unknown, and closing the screen then would look like a crash.
 */
class WidgetGuildRoleSubscriptionEntryPointViewModel(
    private val connectivityStore: StoreConnectivity = StoreStream.getConnectivity(),
    private val guildsStore: StoreGuilds = StoreStream.getGuilds(),
    /**
     * `StoreStream` does not construct or publish [StoreSubscriptions] yet, so the default is
     * `null` and [StoreState.hasUserActiveSubscription] stays unknown — which only suppresses the
     * analytics event below, never any UI. Pass the real store once it is wired up.
     */
    private val subscriptionsStore: StoreSubscriptions? = null,
    private val guildRoleSubscriptionsStore: StoreGuildRoleSubscriptions =
        StoreStream.getGuildRoleSubscriptions(),
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : AppViewModel<WidgetGuildRoleSubscriptionEntryPointViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(
        val isConnected: Boolean,
        val isAccessible: Boolean,
        val guild: Guild?,
        val hasUserActiveSubscription: Boolean?,
        val guildRoleSubscriptionGroupListingId: Long?,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()
        object Invalid : ViewState()
        data class Valid(val guild: Guild) : ViewState()
    }

    private var storeObservableSubscription: Subscription? = null
    private var trackingSourceLocation: String? = null
    private var hasSubmittedAnalytics: Boolean = false

    init {
        subscriptionsStore?.fetchSubscriptions()
    }

    /**
     * (Re)binds the screen to [id]. Called from `onCreateView`, so the previous subscription is
     * dropped first — the fragment is re-created on every configuration change while the view
     * model is not.
     */
    fun setGuildId(id: Long) {
        storeObservableSubscription?.unsubscribe()
        guildRoleSubscriptionsStore.fetchGuildRoleSubscriptionGroupsForGuild(id)
        observeStoreState(id)
            .distinctUntilChanged()
            .computationLatest()
            .ui(this)
            .appSubscribe(
                WidgetGuildRoleSubscriptionEntryPointViewModel::class.java,
                subscriptionHandler = { storeObservableSubscription = it },
            ) { handleStoreState(it) }
    }

    /** Resetting [hasSubmittedAnalytics] lets the same screen report a second, different entry. */
    fun setTrackingSourceLocation(trackingSourceLocation: String? = DEFAULT_TRACKING_SOURCE_LOCATION) {
        if (this.trackingSourceLocation != trackingSourceLocation) hasSubmittedAnalytics = false
        this.trackingSourceLocation = trackingSourceLocation
    }

    private fun handleStoreState(state: StoreState) {
        if (state.guild != null && state.isAccessible) {
            updateViewState(ViewState.Valid(state.guild))
        } else if (state.isConnected) {
            updateViewState(ViewState.Invalid)
        }

        val location = trackingSourceLocation
        val listingId = state.guildRoleSubscriptionGroupListingId
        val hasActiveSubscription = state.hasUserActiveSubscription
        if (
            hasActiveSubscription != null &&
            listingId != null &&
            !location.isNullOrBlank() &&
            !hasSubmittedAnalytics
        ) {
            trackUpsellOpened(listingId, hasActiveSubscription, location)
            hasSubmittedAnalytics = true
        }
    }

    /**
     * Reference: `AnalyticsTracker.guildRoleSubscriptionUpsellOpened`, which builds the generated
     * `TrackRoleSubscriptionListingUpsellPageViewedV3` schema object. The generated analytics
     * classes are not reconstructed, so the same event name and fields are sent as a property map.
     */
    private fun trackUpsellOpened(
        guildRoleSubscriptionGroupListingId: Long,
        hasUserActiveSubscriptions: Boolean,
        location: String,
    ) {
        AnalyticsTracker.tracker.track(
            ANALYTICS_EVENT_UPSELL_VIEWED,
            mapOf(
                "role_subscription_group_listing_id" to guildRoleSubscriptionGroupListingId,
                "role_subscription_listing_ids" to emptyList<Long>(),
                "is_premium_member" to hasUserActiveSubscriptions,
                "location" to location,
            ),
        )
    }

    private fun observeStoreState(guildId: Long): Observable<StoreState> {
        val updateSources = listOfNotNull(
            connectivityStore,
            guildsStore,
            subscriptionsStore,
            guildRoleSubscriptionsStore,
        ).toTypedArray()
        return observationDeck.connectRx(*updateSources) {
            val groupListing = (
                guildRoleSubscriptionsStore.getGuildRoleSubscriptionState(guildId)
                    as? StoreGuildRoleSubscriptions.GuildRoleSubscriptionGroupState.Loaded
                )?.guildRoleSubscriptionGroupListing
            val subscriptions = (subscriptionsStore?.getSubscriptions()
                as? StoreSubscriptions.SubscriptionsState.Loaded)?.subscriptions
            StoreState(
                isConnected = connectivityStore.isConnected(),
                isAccessible = GuildRoleSubscriptionsFeatureFlag.INSTANCE
                    .canGuildSeePremiumMemberships(guildId),
                guild = guildsStore.getGuild(guildId),
                hasUserActiveSubscription = if (subscriptions != null && groupListing != null) {
                    groupListing.hasUserActiveSubscriptionFor(subscriptions)
                } else {
                    null
                },
                guildRoleSubscriptionGroupListingId = groupListing?.id,
            )
        }
    }

    companion object {
        const val DEFAULT_TRACKING_SOURCE_LOCATION = "Role Subscriptions Tab"
        private const val ANALYTICS_EVENT_UPSELL_VIEWED =
            "role_subscription_listing_upsell_page_viewed_v3"
    }
}
