package com.discord.stores

import com.android.billingclient.api.SkuDetails
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.billing.GooglePlayInAppSku
import com.discord.utilities.billing.premiumGifts
import rx.Observable

/**
 * Play Billing `SkuDetails` — localised titles, prices and currencies — keyed by the Play product
 * id they were queried with.
 *
 * The store is a pure cache: the billing client owns the query, and [updateSkuDetails] merges
 * whatever it produced into the existing map rather than replacing it, because in-app products and
 * subscriptions are queried separately and must not evict each other.
 *
 * **Product ids are never declared here.** They come from
 * [com.discord.utilities.billing.GooglePlayInAppSku] entries, which is where Manticord-owned Play
 * product configuration belongs; this store only sees ids that a query already returned.
 */
class StoreGooglePlaySkuDetails(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val dispatcher: Dispatcher,
    /**
     * Mirrors reference `GooglePlayInAppSkus.populateSkuDetails`: pushes the freshly fetched
     * details back onto the catalog entries so a price can be read without going through the store.
     */
    private val populateSkuDetails: (Map<String, SkuDetails>) -> Unit = ::populateInAppSkuCatalog,
) : StoreV2() {

    sealed class State {
        object Uninitialized : State()
        object Failure : State()
        data class Loaded(val skuDetails: Map<String, SkuDetails>) : State()
    }

    private var skuState: State = State.Uninitialized
    private var skuStateSnapshot: State = State.Uninitialized

    fun getState(): State = skuStateSnapshot

    fun observeState(): Observable<State> =
        observationDeck.connectRx(this) { getState() }.distinctUntilChanged()

    fun updateSkuDetails(skuDetails: List<SkuDetails>) = dispatcher.schedule {
        handleFetchSuccess(skuDetails)
    }

    fun handleError() = dispatcher.schedule { handleFetchError() }

    /**
     * Merges into the previous map. A failure state is *not* preserved: a later successful query
     * starts from an empty map, matching the reference's `as? Loaded` narrowing.
     */
    @StoreThread
    fun handleFetchSuccess(newSkuDetails: List<SkuDetails>) {
        val merged = (skuState as? State.Loaded)?.skuDetails.orEmpty().toMutableMap()
        merged.putAll(newSkuDetails.associateBy(SkuDetails::getSku))
        populateSkuDetails(merged)
        skuState = State.Loaded(merged)
        markChanged()
    }

    @StoreThread
    fun handleFetchError() {
        skuState = State.Failure
        markChanged()
    }

    @StoreThread
    internal fun handlePreLogout() {
        skuState = State.Uninitialized
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        val state = skuState
        skuStateSnapshot = when (state) {
            is State.Loaded -> state.copy(skuDetails = HashMap(state.skuDetails))
            State.Uninitialized, State.Failure -> state
        }
    }
}

/**
 * Writes each fetched [SkuDetails] onto the matching [GooglePlayInAppSku] in the app's catalog.
 *
 * Reference `GooglePlayInAppSkus.populateSkuDetails`. The catalog itself lives in
 * `utilities/billing/GooglePlayInAppSku.kt`; this walks it rather than owning an id index so that
 * the only place product ids appear stays that file.
 */
private fun populateInAppSkuCatalog(skuDetails: Map<String, SkuDetails>) {
    premiumGifts.forEach { sku: GooglePlayInAppSku ->
        skuDetails[sku.paymentGatewaySkuId]?.let { details -> sku.skuDetails = details }
    }
}
