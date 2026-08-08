package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.networking.NetworkMonitor
import com.discord.utilities.time.Clock
import org.junit.Assert.assertEquals
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreConnectivityTest {
    @Test
    fun coldGatewayConnectionIsVisibleImmediately() {
        val store = StoreConnectivity(Dispatcher(TestScheduler()), Clock { 0L }, ObservationDeck())

        assertEquals(StoreConnectivity.DelayedState(StoreConnectivity.State.CONNECTING, 0L), store.getState())
        store.handleDeviceNetworkStateUpdated(NetworkMonitor.State.OFFLINE)
        store.handleDeviceNetworkStateUpdated(NetworkMonitor.State.ONLINE)
        assertEquals(StoreConnectivity.DelayedState(StoreConnectivity.State.CONNECTING, 0L), store.getState())
    }
}
