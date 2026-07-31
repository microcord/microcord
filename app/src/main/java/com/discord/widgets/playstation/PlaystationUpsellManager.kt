package com.discord.widgets.playstation

import android.content.SharedPreferences
import com.discord.models.user.User
import com.discord.stores.StoreExperiments
import com.discord.stores.StoreUserConnections
import com.discord.utilities.platform.Platform
import com.discord.utilities.time.Clock
import com.discord.utilities.user.UserUtils.getAgeMs

/**
 * Decides whether to offer "connect your PlayStation account".
 *
 * Four gates, all of which must pass: the account is at least a week old, no PlayStation
 * connection exists yet, the upsell has never been shown on this install, and the viewer is in
 * bucket 1 of the connected-accounts experiment.
 *
 * The "never shown" flag is a plain [SharedPreferences] boolean and is deliberately *not* synced —
 * the reference treats it as per-install, so a reinstall may offer it once more.
 */
class PlaystationUpsellManager(
    private val sharedPreferences: SharedPreferences,
    private val experimentStore: StoreExperiments,
    private val clock: Clock,
) {

    fun canShow(user: User, connectedAccountsState: StoreUserConnections.State): Boolean =
        isAccountOldEnough(user) &&
            isUserMissingPlaystationAccountIntegration(connectedAccountsState) &&
            !hasBeenShown() &&
            canSeePlaystationUpsells(experimentStore)

    fun setHasBeenShown() {
        sharedPreferences.edit().putBoolean(SHOWN_CACHE_KEY, true).apply()
    }

    private fun hasBeenShown(): Boolean = sharedPreferences.getBoolean(SHOWN_CACHE_KEY, false)

    private fun isAccountOldEnough(user: User): Boolean = user.getAgeMs(clock) > 604_800_000L

    /**
     * `Loading` is treated as "already connected" — an unknown connection list must never produce
     * an upsell we would immediately have to retract.
     */
    private fun isUserMissingPlaystationAccountIntegration(
        connectedAccountsState: StoreUserConnections.State,
    ): Boolean {
        if (connectedAccountsState == StoreUserConnections.State.Loading) return false
        return connectedAccountsState.none { Platform.from(it) == Platform.PLAYSTATION }
    }

    companion object {
        private const val SHOWN_CACHE_KEY = "HAS_PLAYSTATION_UPSELL_BEEN_SHOWN"
    }
}
