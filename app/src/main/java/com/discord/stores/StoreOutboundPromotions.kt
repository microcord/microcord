package com.discord.stores

import com.discord.api.premium.ClaimedOutboundPromotion
import com.discord.api.premium.OutboundPromotion
import com.discord.models.domain.ModelPayload
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.user.UserUtils.isPremiumTier2
import rx.Observable

/**
 * Partner promotions offered to Nitro subscribers, plus the unread badge on the Nitro tab.
 *
 * Loaded once per connection, from `handleConnectionOpen`, and only for a Nitro (tier 2) account —
 * anyone else is put straight into `Loaded(emptyList(), 0)` without a request.
 *
 * Two pieces of persisted state drive what the user sees:
 *
 * - [LATEST_SEEN_PROMO_DATE] — the newest promo start date the user has looked at. Everything newer
 *   counts towards [State.Loaded.unseenCount], which is the badge.
 * - [CONSUMED_ENTITLEMENT_PROMO_ID] — the one promotion this account has already redeemed. It is
 *   filtered out of the list. Resolving it costs an entitlements call, so the answer is cached in
 *   prefs and the call is skipped forever after.
 */
class StoreOutboundPromotions(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
    /**
     * Reference `OutboundPromosPreviewFeatureFlag`, a staff-only switch that swaps the live
     * promotions endpoint for the preview one. `StoreExperiments`-backed flags for this surface do
     * not exist yet, so it defaults to off.
     */
    private val previewFeatureFlagEnabled: () -> Boolean = { false },
) : StoreV2() {

    sealed class State {
        open fun deepCopy(): State = this

        object Loading : State()
        object Failed : State()

        data class Loaded(
            val validActivePromotions: List<OutboundPromotion>,
            val unseenCount: Int,
        ) : State() {
            override fun deepCopy(): Loaded = copy(validActivePromotions = ArrayList(validActivePromotions))
        }
    }

    private var state: State = State.Loading
    private var stateSnapshot: State = State.Loading

    fun getState(): State = stateSnapshot

    fun getUnseenCount(): Int = (getState() as? State.Loaded)?.unseenCount ?: 0

    fun observeState(): Observable<State> =
        observationDeck.connectRx(this) { getState() }.distinctUntilChanged()

    fun observeUnseenCount(): Observable<Int> =
        observationDeck.connectRx(this) { getUnseenCount() }.distinctUntilChanged()

    /** Pass-throughs; neither result is cached, because both are used once by a single screen. */
    fun fetchClaimedOutboundPromotions(): Observable<List<ClaimedOutboundPromotion>> =
        restApi()?.getClaimedOutboundPromotions() ?: Observable.empty()

    fun claimOutboundPromotion(promotionId: Long): Observable<ClaimedOutboundPromotion> =
        restApi()?.claimOutboundPromotion(promotionId) ?: Observable.empty()

    @StoreThread
    internal fun handleConnectionOpen(readyPayload: ModelPayload) {
        val me = readyPayload.me
        if (me == null || !me.isPremiumTier2) {
            state = State.Loaded(emptyList(), 0)
            markChanged()
            return
        }
        state = State.Loading
        markChanged()

        val api = restApi()
        if (api == null) {
            state = State.Failed
            markChanged()
            return
        }
        val promotions =
            if (previewFeatureFlagEnabled()) api.getAllPreviewPromotions()
            else api.getAllActiveOutboundPromotions()

        promotions
            .restSubscribeOn(false)
            .flatMap(::excludeConsumedPromotion)
            .appSubscribe(
                StoreOutboundPromotions::class.java,
                errorHandler = {
                    dispatcher.schedule {
                        state = State.Failed
                        markChanged()
                    }
                },
            ) { validActivePromos ->
                dispatcher.schedule {
                    state = State.Loaded(validActivePromos, unseenCount(validActivePromos))
                    markChanged()
                }
            }
    }

    /**
     * Marks every currently loaded promotion as seen by recording the newest start date.
     *
     * The *newest* rather than "now" so that a promotion whose start date is in the future still
     * counts as unseen when it opens.
     */
    fun markSeen() = dispatcher.schedule {
        val loaded = state as? State.Loaded ?: return@schedule
        val promos = loaded.validActivePromotions
        val newest = promos.maxByOrNull { it.startDate } ?: return@schedule
        prefs.edit().putLong(LATEST_SEEN_PROMO_DATE, newest.startDate.dateTimeMillis).apply()
        state = State.Loaded(promos, unseenCount(promos))
        markChanged()
    }

    @StoreThread
    internal fun handlePreLogout() {
        state = State.Loading
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        stateSnapshot = state.deepCopy()
    }

    /**
     * Removes the promotion this account already redeemed.
     *
     * Once the answer is cached, the entitlements call is skipped entirely — that is why the cached
     * branch is a plain `Observable.fromCallable` and the uncached one writes the id back to prefs
     * before mapping. An empty promo list short-circuits both.
     */
    private fun excludeConsumedPromotion(
        activePromos: List<OutboundPromotion>,
    ): Observable<List<OutboundPromotion>> {
        if (prefs.contains(CONSUMED_ENTITLEMENT_PROMO_ID)) {
            return Observable.fromCallable {
                val consumedId = prefs.getLong(CONSUMED_ENTITLEMENT_PROMO_ID, NO_PROMO_ID)
                activePromos.filter { it.id != consumedId }
            }
        }
        if (activePromos.isEmpty()) return Observable.just(activePromos)
        val api = restApi() ?: return Observable.just(activePromos)
        return api.getMyEntitlements(OUTBOUND_PROMOTIONS_APPLICATION_ID, excludeConsumed = false)
            .restSubscribeOn(false)
            .map { entitlements ->
                entitlements
                    .firstOrNull { it.consumed == true && it.promotionId != null }
                    ?.promotionId
                    ?: NO_PROMO_ID
            }
            .doOnNext { consumedPromoId ->
                prefs.edit().putLong(CONSUMED_ENTITLEMENT_PROMO_ID, consumedPromoId).apply()
            }
            .map { consumedPromoId -> activePromos.filterNot { it.id == consumedPromoId } }
    }

    private fun unseenCount(promos: List<OutboundPromotion>): Int {
        val latestSeen = prefs.getLong(LATEST_SEEN_PROMO_DATE, NO_SEEN_PROMO_DATE)
        return promos.count { it.startDate.dateTimeMillis > latestSeen }
    }

    companion object {
        private const val CONSUMED_ENTITLEMENT_PROMO_ID = "CONSUMED_ENTITLEMENT_CODE"
        private const val LATEST_SEEN_PROMO_DATE = "LATEST_SEEN_PROMO_DATE"
        private const val NO_PROMO_ID = -1L
        private const val NO_SEEN_PROMO_DATE = -1L

        /**
         * Application whose entitlements record "this account already redeemed a promotion".
         *
         * **Needs Manticord-owned configuration.** The reference hardcodes Discord's own
         * promotions application id; per the project's billing rules that value is not reproduced
         * here. `0L` makes the entitlements lookup return nothing, so no promotion is ever filtered
         * out as consumed — the list is otherwise fully functional. Replace with the
         * Manticord-owned application id when one exists.
         */
        const val OUTBOUND_PROMOTIONS_APPLICATION_ID = 0L
    }
}
