package com.discord.stores

import com.discord.api.requiredaction.UserRequiredActionUpdate
import com.discord.models.domain.ModelPayload
import com.discord.models.requiredaction.RequiredAction
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

/**
 * The single gate the server says the account must clear before the app is usable.
 *
 * `READY.required_action` seeds it and `USER_REQUIRED_ACTION_UPDATE` replaces it. Only one action
 * is ever pending, which is why this is a scalar rather than the [com.discord.models.authentication.AuthState]
 * set: the login response carries a *list* of actions that gate the token, this carries the one
 * that gates the session.
 *
 * The reference deliberately coerces `REQUIRE_CAPTCHA` into `REQUIRE_VERIFIED_PHONE` — 126.21 has
 * no captcha gate screen, and phone verification is what actually clears the server-side flag.
 */
class StoreUserRequiredActions(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {
    private var userRequiredAction: RequiredAction = RequiredAction.NONE

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload?) {
        updateUserRequiredAction(RequiredAction.fromApiString(payload?.requiredAction))
        markChanged()
    }

    @StoreThread
    fun handleUserRequiredActionUpdate(userRequiredActionUpdate: UserRequiredActionUpdate) {
        updateUserRequiredAction(RequiredAction.fromApiString(userRequiredActionUpdate.requiredAction))
        markChanged()
    }

    /**
     * The reference has no snapshot for this field and reads it live from the observer callback,
     * which only runs after a dispatch completes. Kept identical so the value an observer sees is
     * never one dispatch behind the routing decision that depends on it.
     */
    fun observeUserRequiredAction(): Observable<RequiredAction> =
        observationDeck.connectRx(this) { userRequiredAction }

    @StoreThread
    private fun updateUserRequiredAction(userRequiredAction: RequiredAction) {
        this.userRequiredAction = if (userRequiredAction == RequiredAction.REQUIRE_CAPTCHA) {
            RequiredAction.REQUIRE_VERIFIED_PHONE
        } else {
            userRequiredAction
        }
    }
}
