package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider

class StoreV2DispatchHandler(
    private val storesV2: List<StoreV2>,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : DispatchHandler {
    @StoreThread
    override fun onDispatchEnded() {
        val updates = HashSet<ObservationDeck.UpdateSource>()
        storesV2.forEach { store ->
            if (store.updateSources.isNotEmpty()) {
                updates.addAll(store.updateSources)
                store.snapshotData()
                store.onDispatchEnded()
            }
        }
        if (updates.isNotEmpty()) observationDeck.notify(updates)
    }
}
