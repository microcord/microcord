package com.discord.stores

import com.discord.api.user.User
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

/**
 * Pending state of the account-settings multi-factor flows.
 *
 * This is *not* the login MFA challenge (that lives in `StoreAuthentication.authMFA`). It tracks
 * the enable/disable request the settings screen has started but the server has not confirmed
 * yet, plus the SMS-backup toggle, so the UI can show progress while `USER_UPDATE` is in flight.
 *
 * Any `USER_UPDATE` for the current user clears the pending state — that event is the
 * confirmation, whichever way it went.
 */
class StoreMFA(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val currentUserId: () -> Long,
) : StoreV2() {
    enum class MFAActivationState { PENDING_ENABLED, PENDING_DISABLED, NONE }

    data class State(
        val activationState: MFAActivationState,
        val isTogglingSMSBackup: Boolean,
    )

    private var state = State(MFAActivationState.NONE, false)

    @StoreThread
    fun handleUserUpdated(user: User) {
        if (user.id != currentUserId()) return
        state = State(MFAActivationState.NONE, false)
        markChanged()
    }

    fun observeState(): Observable<State> =
        observationDeck.connectRx(this) { state }.distinctUntilChanged()

    fun togglingSMSBackup() = dispatcher.schedule {
        state = state.copy(isTogglingSMSBackup = true)
        markChanged()
    }

    fun updatePendingMFAState(newActivationState: MFAActivationState) = dispatcher.schedule {
        state = state.copy(activationState = newActivationState)
        markChanged()
    }
}
