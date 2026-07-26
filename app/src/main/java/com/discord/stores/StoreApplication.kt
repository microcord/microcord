package com.discord.stores

import com.discord.api.application.Application
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * Full application records fetched by id and cached for the session.
 *
 * This is the *application* record (`GET /applications?application_ids=`), not the command-surface
 * projection that [StoreApplicationCommands] deals in — rich presence, game invites and the
 * activity headers all need the description/cover/splash fields that only this endpoint carries.
 *
 * [applicationsLoading] is what stops N rows for the same game from firing N requests: a row binds,
 * calls [fetchIfNonexisting], and every later bind is a no-op until the response lands. A failure
 * clears the in-flight marker without caching anything, so the next bind retries.
 */
class StoreApplication(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
) : StoreV2() {

    private val applications = HashMap<Long, Application>()
    private val applicationsLoading = HashSet<Long>()
    private var applicationsSnapshot: Map<Long, Application> = emptyMap()

    fun getApplication(applicationId: Long): Application? = applicationsSnapshot[applicationId]

    fun getApplications(): Map<Long, Application> = applicationsSnapshot

    /** Requests [appId] unless it is already cached or already in flight. */
    fun fetchIfNonexisting(appId: Long) = dispatcher.schedule {
        if (applications.containsKey(appId) || applicationsLoading.contains(appId)) return@schedule
        applicationsLoading.add(appId)
        val api = restApi()
        if (api == null) {
            applicationsLoading.remove(appId)
            return@schedule
        }
        api.getApplications(appId)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreApplication::class.java,
                errorHandler = { dispatcher.schedule { handleFetchFailure(appId) } },
            ) { results -> dispatcher.schedule { handleFetchSuccess(appId, results) } }
    }

    @StoreThread
    internal fun handleFetchSuccess(appId: Long, results: List<Application>) {
        applicationsLoading.remove(appId)
        // The endpoint takes a list of ids and answers with a list; only the requested record is
        // stored, exactly as the reference does.
        val application = results.firstOrNull { it.id == appId } ?: return
        applications[appId] = application
        markChanged()
    }

    @StoreThread
    internal fun handleFetchFailure(appId: Long) {
        applicationsLoading.remove(appId)
    }

    /**
     * A null id yields a single `null` without touching the cache, so a caller holding an optional
     * application id does not need its own branch.
     */
    fun observeApplication(appId: Long?): Observable<Application?> {
        if (appId == null) return Observable.just(null)
        fetchIfNonexisting(appId)
        return observationDeck.connectRx(this) { applicationsSnapshot[appId] }
    }

    fun observeApplications(applicationIds: Collection<Long>): Observable<Map<Long, Application>> =
        observationDeck.connectRx(this) {
            applicationsSnapshot.filterKeys(applicationIds::contains)
        }.distinctUntilChanged()

    @StoreThread
    internal fun handlePreLogout() {
        applications.clear()
        applicationsLoading.clear()
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        applicationsSnapshot = HashMap(applications)
    }
}
