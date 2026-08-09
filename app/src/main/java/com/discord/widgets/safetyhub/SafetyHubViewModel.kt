package com.discord.widgets.safetyhub

import com.discord.api.safetyhub.AccountStandingState
import com.discord.api.safetyhub.SafetyRecordClassification
import com.discord.app.AppViewModel
import com.discord.stores.StoreSafetyHub
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeUtils
import rx.Observable

class SafetyHubViewModel(
    private val safetyHub: StoreSafetyHub = StoreStream.getSafetyHub(),
    private val clock: Clock = ClockFactory.get(),
    storeObservable: Observable<StoreSafetyHub.State> = safetyHub.observeState(),
) : AppViewModel<SafetyHubViewModel.ViewState>(ViewState.Loading) {

    data class Violation(
        val classification: SafetyRecordClassification,
        val isExpired: Boolean,
    ) {
        val id: String get() = classification.id
        val guildName: String? get() = classification.guildMetadata?.name
        val expiresAtMs: Long? get() = classification.maxExpirationTime
            ?.let(TimeUtils::parseUTCDate)
            ?.takeIf { it > 0 }
    }

    sealed class ViewState {
        data object Loading : ViewState()
        data object Failed : ViewState()
        data class Loaded(
            val standing: AccountStandingState,
            val active: List<Violation>,
            val expired: List<Violation>,
            val isAppealEligible: Boolean,
        ) : ViewState()
    }

    init {
        storeObservable.ui(this).appSubscribe(SafetyHubViewModel::class.java) { state ->
            updateViewState(
                when {
                    state.hub != null -> loaded(state)
                    state.hasError -> ViewState.Failed
                    else -> ViewState.Loading
                },
            )
        }
        safetyHub.fetch()
    }

    fun retry() = safetyHub.fetch(force = true)

    private fun loaded(state: StoreSafetyHub.State): ViewState.Loaded {
        val hub = checkNotNull(state.hub)
        val now = clock.currentTimeMillis()
        // A violation with no expiry never stops counting, so it is never in the expired list.
        val (expired, active) = state.classifications
            .map { Violation(it, isExpired = it.hasExpiredBy(now)) }
            .partition(Violation::isExpired)
        return ViewState.Loaded(
            standing = hub.accountStanding.state,
            active = active,
            expired = expired,
            isAppealEligible = hub.isAppealEligible,
        )
    }

    private fun SafetyRecordClassification.hasExpiredBy(now: Long): Boolean {
        val expiry = maxExpirationTime?.let(TimeUtils::parseUTCDate)?.takeIf { it > 0 } ?: return false
        return expiry <= now
    }
}
