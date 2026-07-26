package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

/**
 * Whether the chat input's autocomplete list is currently on screen.
 *
 * A single boolean deserves a store because several unrelated surfaces depend on it — the send
 * button, the expression tray toggle and the back-press handler all have to know that the input is
 * showing suggestions rather than text — and none of them owns the autocomplete view.
 */
class StoreAutocomplete(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val dispatcher: Dispatcher,
) : StoreV2() {

    private var autocompleteVisible: Boolean = false

    fun observeAutocompleteVisibility(): Observable<Boolean> =
        observationDeck.connectRx(this) { autocompleteVisible }.distinctUntilChanged()

    fun setAutocompleteVisible(visible: Boolean) = dispatcher.schedule {
        autocompleteVisible = visible
        markChanged()
    }

    /**
     * The reference has no reset path: the flag is view-driven and the composer always publishes
     * `false` when it tears down. Logout goes through the same route, so this only exists to make
     * the store's lifecycle explicit for [StoreStream].
     */
    @StoreThread
    internal fun handlePreLogout() {
        autocompleteVisible = false
        markChanged()
    }
}
