package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import java.util.concurrent.TimeUnit
import rx.Observable

/**
 * Single source of truth for "the gateway session has an open, hydrated connection".
 *
 * Before this store existed the `handleConnectionOpen` signal was re-derived independently by every
 * store that needed it. Stores keep their own `handleConnectionOpen(payload)` hydration entry
 * points; this store owns only the boolean *is the connection open* fact, so screens can gate on a
 * single observable instead of guessing from their own store.
 */
class StoreConnectionOpen(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    private var connectionOpen: Boolean by MarkChangedDelegate(false)

    @StoreThread
    fun handleConnectionOpen() {
        connectionOpen = true
    }

    @StoreThread
    fun handleConnected(connected: Boolean) {
        if (connected) return
        connectionOpen = false
    }

    fun isConnectionOpen(): Boolean = connectionOpen

    /**
     * @param delayForUI holds each value back by [UI_DELAY_MS] so a screen observing this does not
     *   render before the stores hydrated by the same READY have finished their dispatch.
     */
    @JvmOverloads
    fun observeConnectionOpen(delayForUI: Boolean = true): Observable<Boolean> {
        val source = observationDeck.connectRx(this, generator = { connectionOpen })
        return if (delayForUI) source.delay(UI_DELAY_MS, TimeUnit.MILLISECONDS) else source
    }

    private companion object {
        const val UI_DELAY_MS = 100L
    }
}
