package com.discord.stores

import com.discord.api.guildrolesubscription.GuildRoleSubscriptionFreeTrialIntervalTypes
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionGroupListing
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionTierFreeTrial
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionTierListing
import com.discord.api.guildrolesubscription.SubscriptionTrialInterval
import com.discord.api.premium.PriceTierType
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * A guild's monetisation setup: the role-subscription *group* (one per guild), the *tiers* inside
 * it, the optional free trial per tier, and the catalogue of price points the creator may pick.
 *
 * The group and its tiers are stored **denormalised on purpose**. The API returns the group with
 * its tiers inlined, but tier-level gateway updates arrive on their own, so the store splits them:
 * [guildRoleSubscriptionGroups] keeps the group with `subscriptionListings` blanked, and
 * [guildRoleSubscriptionTiers] keeps the tiers keyed by id. A tier update therefore touches one map
 * entry instead of rebuilding the group. [getGuildRoleSubscriptionState] re-joins them on read,
 * ordering the tiers by the group's own `subscriptionListingsIds` so the creator's ordering is
 * preserved and tiers that have not arrived yet are simply skipped.
 *
 * Free trials are fetched in the same round-trip as the group, but only for guilds the trial
 * experiment applies to — hence [isGuildEligibleForTrials], which stands in for the reference's
 * `GuildRoleSubscriptionsFeatureFlag`. When it is false the trial half of the pair is `null`,
 * which [handleGuildRoleSubscriptionFreeTrialListFetch] treats as "this guild has no trials" and
 * evicts the entry — distinct from an empty list.
 */
class StoreGuildRoleSubscriptions(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
    /**
     * Reference: `GuildRoleSubscriptionsFeatureFlag.isGuildEligibleForGuildRoleSubscriptionTrials`.
     * That flag is experiment-gated and no `StoreExperiments`-backed flag object exists yet, so the
     * default is the safe one: never ask for trials.
     */
    private val isGuildEligibleForTrials: (guildId: Long) -> Boolean = { false },
) : StoreV2() {

    sealed class GuildRoleSubscriptionGroupState {
        object Loading : GuildRoleSubscriptionGroupState()
        data class Failed(val errorMessage: String? = null) : GuildRoleSubscriptionGroupState()

        /** `null` listing means "this guild has never set monetisation up". */
        data class Loaded(
            val guildRoleSubscriptionGroupListing: GuildRoleSubscriptionGroupListing?,
        ) : GuildRoleSubscriptionGroupState()
    }

    /**
     * Declared by the reference alongside the group state but never assigned to a field; kept so
     * the type surface matches when the trial-management screens are rebuilt.
     */
    sealed class GuildRoleSubscriptionFreeTrialsState {
        object Uninitialized : GuildRoleSubscriptionFreeTrialsState()
        object Loading : GuildRoleSubscriptionFreeTrialsState()
        data class Failed(val errorMessage: String? = null) : GuildRoleSubscriptionFreeTrialsState()
        data class Loaded(
            val freeTrials: Map<Long, GuildRoleSubscriptionTierFreeTrial>? = null,
        ) : GuildRoleSubscriptionFreeTrialsState()
    }

    sealed class PriceTierState {
        object Uninitialized : PriceTierState()
        object Loading : PriceTierState()
        data class Failed(val errorMessage: String? = null) : PriceTierState()
        data class Loaded(val priceTiers: List<Int>) : PriceTierState()
    }

    private var priceTierState: PriceTierState = PriceTierState.Uninitialized
    private var priceTierStateSnapshot: PriceTierState = PriceTierState.Uninitialized

    private val guildRoleSubscriptionGroups = LinkedHashMap<Long, GuildRoleSubscriptionGroupState>()
    private var guildRoleSubscriptionGroupsSnapshot: Map<Long, GuildRoleSubscriptionGroupState> = emptyMap()

    private val guildRoleSubscriptionTiers =
        LinkedHashMap<Long, MutableMap<Long, GuildRoleSubscriptionTierListing>>()
    private var guildRoleSubscriptionTiersSnapshot: Map<Long, Map<Long, GuildRoleSubscriptionTierListing>> =
        emptyMap()

    private val guildRoleSubscriptionFreeTrials =
        LinkedHashMap<Long, MutableMap<Long, GuildRoleSubscriptionTierFreeTrial>>()
    private var guildRoleSubscriptionFreeTrialsSnapshot: Map<Long, Map<Long, GuildRoleSubscriptionTierFreeTrial>> =
        emptyMap()

    // region reads

    fun getPriceTiersState(): PriceTierState = priceTierStateSnapshot

    /** Re-joins the stored group with its stored tiers; see the class doc. */
    fun getGuildRoleSubscriptionState(guildId: Long): GuildRoleSubscriptionGroupState? {
        val state = guildRoleSubscriptionGroupsSnapshot[guildId] ?: return null
        if (state !is GuildRoleSubscriptionGroupState.Loaded) return state
        val listing = state.guildRoleSubscriptionGroupListing
        val tiers = guildRoleSubscriptionTiersSnapshot[guildId]
        val joined = listing?.subscriptionListingsIds.orEmpty().mapNotNull { id -> tiers?.get(id) }
        return GuildRoleSubscriptionGroupState.Loaded(
            listing?.copy(subscriptionListings = joined),
        )
    }

    fun getGuildRoleSubscriptionFreeTrials(guildId: Long): Map<Long, GuildRoleSubscriptionTierFreeTrial>? =
        guildRoleSubscriptionFreeTrialsSnapshot[guildId]

    fun getGuildRoleSubscriptionFreeTrialForListing(
        guildId: Long,
        listingId: Long,
    ): GuildRoleSubscriptionTierFreeTrial? = guildRoleSubscriptionFreeTrialsSnapshot[guildId]?.get(listingId)

    /** The choices the free-trial editor offers; `null` is "unlimited". */
    fun getGuildRoleSubscriptionFreeTrialActiveUserLimitOptions(): List<Int?> =
        listOf(null, 10, 25, 50, 100)

    fun getGuildRoleSubscriptionFreeTrialIntervalOptions(): List<SubscriptionTrialInterval> = listOf(
        SubscriptionTrialInterval(GuildRoleSubscriptionFreeTrialIntervalTypes.DAILY, 1),
        SubscriptionTrialInterval(GuildRoleSubscriptionFreeTrialIntervalTypes.DAILY, 7),
    )

    fun observeGuildRoleSubscriptionState(guildId: Long): Observable<GuildRoleSubscriptionGroupState?> =
        observationDeck.connectRx(this) { getGuildRoleSubscriptionState(guildId) }.distinctUntilChanged()

    fun observePriceTiersState(): Observable<PriceTierState> =
        observationDeck.connectRx(this) { getPriceTiersState() }.distinctUntilChanged()

    fun observeGuildRoleSubscriptionFreeTrials(
        guildId: Long,
    ): Observable<Map<Long, GuildRoleSubscriptionTierFreeTrial>?> =
        observationDeck.connectRx(this) { getGuildRoleSubscriptionFreeTrials(guildId) }.distinctUntilChanged()

    // endregion

    // region fetches

    /** Fetches one known group by id. No-op while a request for this guild is already in flight. */
    fun fetchGuildRoleSubscriptionGroup(guildId: Long, guildRoleSubscriptionGroupId: Long) {
        if (guildRoleSubscriptionGroupsSnapshot[guildId] is GuildRoleSubscriptionGroupState.Loading) return
        val api = restApi() ?: return
        dispatcher.schedule {
            guildRoleSubscriptionGroups[guildId] = GuildRoleSubscriptionGroupState.Loading
            markChanged()
        }
        val source: Observable<Pair<GuildRoleSubscriptionGroupListing?, List<GuildRoleSubscriptionTierFreeTrial>?>> =
            if (isGuildEligibleForTrials(guildId)) {
                Observable.zip(
                    api.getGuildRoleSubscriptionGroupListing(guildId, guildRoleSubscriptionGroupId),
                    api.getGuildRoleSubscriptionFreeTrials(guildId),
                ) { listing, trials -> listing to trials }
            } else {
                api.getGuildRoleSubscriptionGroupListing(guildId, guildRoleSubscriptionGroupId)
                    .map { listing -> listing to null }
            }
        subscribeGroupFetch(guildId, source)
    }

    /** Fetches the guild's group list and keeps the first entry — a guild has at most one group. */
    fun fetchGuildRoleSubscriptionGroupsForGuild(guildId: Long) {
        if (guildRoleSubscriptionGroupsSnapshot[guildId] is GuildRoleSubscriptionGroupState.Loading) return
        val api = restApi() ?: return
        dispatcher.schedule {
            guildRoleSubscriptionGroups[guildId] = GuildRoleSubscriptionGroupState.Loading
            markChanged()
        }
        val source: Observable<Pair<GuildRoleSubscriptionGroupListing?, List<GuildRoleSubscriptionTierFreeTrial>?>> =
            if (isGuildEligibleForTrials(guildId)) {
                Observable.zip(
                    api.getGuildRoleSubscriptionGroupListings(guildId),
                    api.getGuildRoleSubscriptionFreeTrials(guildId),
                ) { listings, trials -> listings.firstOrNull() to trials }
            } else {
                api.getGuildRoleSubscriptionGroupListings(guildId)
                    .map { listings -> listings.firstOrNull() to null }
            }
        subscribeGroupFetch(guildId, source)
    }

    /** Fetched once per session: the catalogue never changes while the app is running. */
    fun fetchPriceTiersIfNonExisting() {
        if (priceTierStateSnapshot is PriceTierState.Loading) return
        if (priceTierStateSnapshot is PriceTierState.Loaded) return
        val api = restApi() ?: return
        dispatcher.schedule {
            priceTierState = PriceTierState.Loading
            markChanged()
        }
        api.getPriceTiers(PriceTierType.GUILD_ROLE_SUBSCRIPTIONS.apiValue)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreGuildRoleSubscriptions::class.java,
                errorHandler = { error ->
                    dispatcher.schedule {
                        priceTierState = PriceTierState.Failed(error.message)
                        markChanged()
                    }
                },
            ) { priceTiers ->
                dispatcher.schedule {
                    priceTierState = PriceTierState.Loaded(priceTiers)
                    markChanged()
                }
            }
    }

    private fun subscribeGroupFetch(
        guildId: Long,
        source: Observable<Pair<GuildRoleSubscriptionGroupListing?, List<GuildRoleSubscriptionTierFreeTrial>?>>,
    ) {
        source.restSubscribeOn(false).appSubscribe(
            StoreGuildRoleSubscriptions::class.java,
            errorHandler = { error ->
                dispatcher.schedule {
                    guildRoleSubscriptionGroups[guildId] =
                        GuildRoleSubscriptionGroupState.Failed(error.message)
                    markChanged()
                }
            },
        ) { (listing, trials) ->
            dispatcher.schedule {
                handleGuildRoleSubscriptionGroupFetch(guildId, listing)
                handleGuildRoleSubscriptionFreeTrialListFetch(guildId, trials)
            }
        }
    }

    // endregion

    // region gateway / mutations

    @StoreThread
    internal fun handleGuildRoleSubscriptionGroupFetch(
        guildId: Long,
        guildRoleSubscriptionGroupListing: GuildRoleSubscriptionGroupListing?,
    ) {
        if (guildRoleSubscriptionGroupListing == null) {
            guildRoleSubscriptionGroups[guildId] = GuildRoleSubscriptionGroupState.Loaded(null)
            guildRoleSubscriptionTiers.remove(guildId)
            markChanged()
            return
        }
        // Tiers are split out of the group; the stored group keeps only their ids.
        guildRoleSubscriptionGroups[guildId] = GuildRoleSubscriptionGroupState.Loaded(
            guildRoleSubscriptionGroupListing.copy(subscriptionListings = null),
        )
        if (guildRoleSubscriptionGroupListing.subscriptionListingsIds.isNullOrEmpty()) {
            guildRoleSubscriptionTiers.remove(guildId)
        } else {
            val tiers = guildRoleSubscriptionTiers.getOrPut(guildId, ::LinkedHashMap)
            guildRoleSubscriptionGroupListing.subscriptionListings?.forEach { tier ->
                tiers[tier.id] = tier
            }
        }
        markChanged()
    }

    @StoreThread
    internal fun handleGuildRoleSubscriptionFreeTrialListFetch(
        guildId: Long,
        guildRoleSubscriptionFreeTrialList: List<GuildRoleSubscriptionTierFreeTrial>?,
    ) {
        if (guildRoleSubscriptionFreeTrialList == null) {
            guildRoleSubscriptionFreeTrials.remove(guildId)
        } else {
            guildRoleSubscriptionFreeTrials[guildId] =
                guildRoleSubscriptionFreeTrialList.associateByTo(
                    LinkedHashMap(),
                    GuildRoleSubscriptionTierFreeTrial::id,
                )
        }
        markChanged()
    }

    fun handleGuildRoleSubscriptionGroupUpdate(
        guildId: Long,
        guildRoleSubscriptionGroupListing: GuildRoleSubscriptionGroupListing,
    ) = dispatcher.schedule {
        guildRoleSubscriptionGroups[guildId] =
            GuildRoleSubscriptionGroupState.Loaded(guildRoleSubscriptionGroupListing)
        markChanged()
    }

    fun handleGuildRoleSubscriptionTierListingUpdate(
        guildId: Long,
        guildRoleSubscriptionTierListing: GuildRoleSubscriptionTierListing,
    ) = dispatcher.schedule {
        guildRoleSubscriptionTiers.getOrPut(guildId, ::LinkedHashMap)[guildRoleSubscriptionTierListing.id] =
            guildRoleSubscriptionTierListing
        markChanged()
    }

    /**
     * A new tier does not appear in the group's `subscriptionListingsIds` until the group is
     * re-fetched, so the tier is stored *and* the group re-requested — otherwise the new tier would
     * be invisible to [getGuildRoleSubscriptionState], which orders by those ids.
     */
    fun handleGuildRoleSubscriptionTierListingCreate(
        guildId: Long,
        guildRoleSubscriptionGroupListingId: Long,
        guildRoleSubscriptionTierListing: GuildRoleSubscriptionTierListing,
    ) {
        handleGuildRoleSubscriptionTierListingUpdate(guildId, guildRoleSubscriptionTierListing)
        fetchGuildRoleSubscriptionGroup(guildId, guildRoleSubscriptionGroupListingId)
    }

    fun handleGuildRoleSubscriptionTierListingDelete(
        guildId: Long,
        guildRoleSubscriptionGroupListingId: Long,
        guildRoleSubscriptionTierListingId: Long,
    ) {
        dispatcher.schedule {
            val tiers = guildRoleSubscriptionTiers[guildId] ?: return@schedule
            tiers.remove(guildRoleSubscriptionTierListingId)
            markChanged()
        }
        fetchGuildRoleSubscriptionGroup(guildId, guildRoleSubscriptionGroupListingId)
    }

    /**
     * Note the key: a trial is stored under the **group listing id**, not the trial's own id. That
     * is what the reference does, and what [getGuildRoleSubscriptionFreeTrialForListing] reads
     * back.
     */
    fun handleGuildRoleSubscriptionTierTrialUpdate(
        guildId: Long,
        guildRoleSubscriptionGroupListingId: Long,
        guildRoleSubscriptionTierFreeTrial: GuildRoleSubscriptionTierFreeTrial,
    ) = dispatcher.schedule {
        guildRoleSubscriptionFreeTrials
            .getOrPut(guildId, ::LinkedHashMap)[guildRoleSubscriptionGroupListingId] =
            guildRoleSubscriptionTierFreeTrial
        markChanged()
    }

    @StoreThread
    internal fun reset() {
        priceTierState = PriceTierState.Uninitialized
        guildRoleSubscriptionGroups.clear()
        guildRoleSubscriptionTiers.clear()
        guildRoleSubscriptionFreeTrials.clear()
        markChanged()
    }

    // endregion

    @StoreThread
    override fun snapshotData() {
        priceTierStateSnapshot = priceTierState
        guildRoleSubscriptionGroupsSnapshot = HashMap(guildRoleSubscriptionGroups)
        guildRoleSubscriptionTiersSnapshot = guildRoleSubscriptionTiers.mapValues { HashMap(it.value) }
        guildRoleSubscriptionFreeTrialsSnapshot =
            guildRoleSubscriptionFreeTrials.mapValues { HashMap(it.value) }
    }
}
