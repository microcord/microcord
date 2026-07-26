package com.discord.stores

import com.discord.models.domain.ModelLibraryApplication
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * The current user's owned applications, keyed by SKU id.
 *
 * The snapshot is deliberately nullable and doubles as the "have we ever fetched" flag:
 * [fetchApplications] only issues a request while it is `null`, and [snapshotData] sets it on the
 * first dispatch after any change. That is why a *failed* fetch still clears the map and marks the
 * store changed — the failure produces an empty (but non-null) snapshot, so the library screen
 * renders "nothing owned" instead of spinning forever, and the request is not retried for the rest
 * of the session.
 *
 * [StoreGifting.acceptGift] re-runs this after a redemption so a newly granted SKU appears.
 */
class StoreLibrary(
    val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
) : StoreV2() {

    private val libraryApplications = LinkedHashMap<Long, ModelLibraryApplication>()
    private var libraryApplicationSnapshot: Map<Long, ModelLibraryApplication>? = null

    fun getApplications(): Map<Long, ModelLibraryApplication>? = libraryApplicationSnapshot

    fun fetchApplications() {
        if (libraryApplicationSnapshot != null) return
        val api = restApi() ?: return
        api.getLibrary()
            .restSubscribeOn(false)
            .appSubscribe(
                StoreLibrary::class.java,
                errorHandler = { dispatcher.schedule { handleFetchFailure() } },
            ) { libraryApps -> dispatcher.schedule { handleFetchSuccess(libraryApps) } }
    }

    @StoreThread
    internal fun handleFetchSuccess(libraryApps: List<ModelLibraryApplication>) {
        libraryApplications.clear()
        libraryApps.forEach { libraryApplications[it.skuId] = it }
        markChanged()
    }

    @StoreThread
    internal fun handleFetchFailure() {
        libraryApplications.clear()
        markChanged()
    }

    fun observeApplications(): Observable<Map<Long, ModelLibraryApplication>?> {
        fetchApplications()
        return observationDeck.connectRx(this) { libraryApplicationSnapshot }
    }

    /**
     * Resets the "already fetched" latch as well as the cache, so the next session re-requests the
     * library for the newly signed-in account.
     *
     * Deliberately does **not** `markChanged()`: a dispatch that marks this store changed ends in
     * [snapshotData], which would publish an empty *non-null* snapshot and re-arm the latch in the
     * closed position. The reference has no reset path at all here; clearing without notifying is
     * the smallest change that makes the next login re-fetch.
     */
    @StoreThread
    internal fun handlePreLogout() {
        libraryApplications.clear()
        libraryApplicationSnapshot = null
    }

    @StoreThread
    override fun snapshotData() {
        libraryApplicationSnapshot = HashMap(libraryApplications)
    }
}
