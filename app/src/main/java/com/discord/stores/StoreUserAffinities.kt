package com.discord.stores

import com.discord.models.domain.ModelUserAffinities
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

class StoreUserAffinities(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val fetchAffinities: ((ModelUserAffinities) -> Unit, () -> Unit) -> Unit,
) : StoreV2() {
    private var affinities = EMPTY
    private var affinitiesSnapshot = EMPTY

    fun getUserAffinities(): ModelUserAffinities = affinitiesSnapshot
    fun observeUserAffinities(): Observable<ModelUserAffinities> =
        observationDeck.connectRx(this) { affinitiesSnapshot }.distinctUntilChanged()
    fun observeAffinityUserIds(): Observable<List<Long>> =
        observeUserAffinities().map { value -> value.userAffinities.map { it.userId } }.distinctUntilChanged()

    @StoreThread
    fun handleConnectionOpen() {
        StoreThreadScheduler.assertCurrentThread()
        fetchAffinities(
            { value -> dispatcher.schedule { handleUserAffinitiesFetchSuccess(value) } },
            {},
        )
    }

    @StoreThread
    fun handleUserAffinitiesFetchSuccess(value: ModelUserAffinities) {
        StoreThreadScheduler.assertCurrentThread()
        affinities = value
        markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        StoreThreadScheduler.assertCurrentThread()
        affinities = EMPTY
        markChanged()
    }

    override fun snapshotData() {
        affinitiesSnapshot = ModelUserAffinities(
            affinities.userAffinities.toList(),
            affinities.inverseUserAffinities.toList(),
        )
    }

    companion object {
        private val EMPTY = ModelUserAffinities(emptyList(), emptyList())
    }
}
