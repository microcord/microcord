package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

private const val PUSH_UPSELL_DISMISS_KEY = "PUSH_NOTIFICATIONS_DISMISS_UPSELL"

class StoreNotificationUpsells(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    fun dismissPushNotificationsUpsell() {
        prefsSessionDurable.edit().putBoolean(PUSH_UPSELL_DISMISS_KEY, true).apply()
        dispatcher.schedule { markChanged() }
    }

    fun getPushNotificationsUpsellDismissed(): Boolean =
        prefsSessionDurable.getBoolean(PUSH_UPSELL_DISMISS_KEY, false)

    fun observePushNotificationUpsellDismissed(): Observable<Boolean> =
        observationDeck.connectRx(this, generator = ::getPushNotificationsUpsellDismissed)
            .distinctUntilChanged()
}
