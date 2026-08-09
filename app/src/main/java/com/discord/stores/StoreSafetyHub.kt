package com.discord.stores

import com.discord.api.safetyhub.AppealIngestionSignal
import com.discord.api.safetyhub.AppealStatus
import com.discord.api.safetyhub.AppealStatusType
import com.discord.api.safetyhub.SafetyHub
import com.discord.api.safetyhub.SafetyRecordClassification
import com.discord.stores.updates.ObservationDeck
import rx.Observable

/**
 * The account's standing and the violations behind it.
 *
 * Reachable both logged in and suspended; which of the two endpoint families answers is decided by
 * [StoreAuthentication.getSuspendedUserToken], so nothing here distinguishes the two cases.
 */
class StoreSafetyHub(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val loader: ((Result<SafetyHub>) -> Unit) -> Unit = { it(Result.success(SafetyHub())) },
    private val reviewer: (String, AppealIngestionSignal, String) -> Observable<Unit> =
        { _, _, _ -> Observable.just(Unit) },
) : StoreV2() {

    data class State(
        val hub: SafetyHub? = null,
        val isLoading: Boolean = false,
        val hasError: Boolean = false,
    ) {
        /** The reference renders one list; a guild violation is only distinguished by its metadata. */
        val classifications: List<SafetyRecordClassification>
            get() = hub?.let { it.classifications + it.guildClassifications }.orEmpty()
    }

    private var state = State()
    private var snapshot = State()

    fun observeState(): Observable<State> =
        observationDeck.connectRx(this) { snapshot }.distinctUntilChanged()

    @JvmOverloads
    fun fetch(force: Boolean = false) {
        if (snapshot.isLoading) return
        if (!force && snapshot.hub != null) return
        dispatcher.schedule { markLoading() }
        loader { result ->
            dispatcher.schedule {
                val hub = result.getOrNull()
                state = if (hub == null) {
                    state.copy(isLoading = false, hasError = true)
                } else {
                    State(hub = hub, isLoading = false, hasError = false)
                }
                markChanged()
            }
        }
    }

    fun requestReview(
        classificationId: String,
        signal: AppealIngestionSignal,
        userInput: String,
    ): Observable<Unit> =
        reviewer(classificationId, signal, userInput)
            .doOnNext { dispatcher.schedule { markAppealPending(classificationId) } }

    @StoreThread
    private fun markLoading() {
        state = state.copy(isLoading = true, hasError = false)
        markChanged()
    }

    /** The response carries nothing to merge, so the pending status is assumed as the reference does. */
    @StoreThread
    private fun markAppealPending(classificationId: String) {
        val hub = state.hub ?: return
        fun mark(classifications: List<SafetyRecordClassification>) = classifications.map {
            if (it.id != classificationId) {
                it
            } else {
                it.copy(appealStatus = AppealStatus(AppealStatusType.REVIEW_PENDING))
            }
        }
        state = state.copy(
            hub = hub.copy(
                classifications = mark(hub.classifications),
                guildClassifications = mark(hub.guildClassifications),
            ),
        )
        markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        state = State()
        markChanged()
    }

    override fun snapshotData() {
        super.snapshotData()
        snapshot = state
    }
}
