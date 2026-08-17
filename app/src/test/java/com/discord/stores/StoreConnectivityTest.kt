package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.networking.NetworkMonitor
import com.discord.utilities.time.Clock
import org.junit.Assert.assertEquals
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreConnectivityTest {
    @Test
    fun connectingAlwaysWaitsOutTheExtendedDelay() {
        val store = StoreConnectivity(Dispatcher(TestScheduler()), Clock { 0L }, ObservationDeck())
        val extended = StoreConnectivity.DELAY_EXTENDED

        assertEquals(StoreConnectivity.DelayedState(StoreConnectivity.State.CONNECTING, extended), store.getState())
        store.handleDeviceNetworkStateUpdated(NetworkMonitor.State.OFFLINE)
        store.handleDeviceNetworkStateUpdated(NetworkMonitor.State.ONLINE)
        assertEquals(StoreConnectivity.DelayedState(StoreConnectivity.State.CONNECTING, extended), store.getState())

        // Having been online once does not shorten it: a guild switch reports as CONNECTING while
        // the channel loads, and that must not flash the banner.
        store.handleConnectionReady(true)
        store.handleChannelMessagesLoading(true)
        assertEquals(
            StoreConnectivity.DelayedState(StoreConnectivity.State.CONNECTING, StoreConnectivity.DELAY_DISCONNECT),
            store.getState(),
        )
    }

    @Test
    fun disconnectShowsConnectingWithinTwoAndAHalfSeconds() {
        val store = StoreConnectivity(Dispatcher(TestScheduler()), Clock { 0L }, ObservationDeck())
        store.handleDeviceNetworkStateUpdated(NetworkMonitor.State.ONLINE)
        store.handleConnectionReady(true)
        store.handleConnected(false)
        assertEquals(
            StoreConnectivity.DelayedState(StoreConnectivity.State.CONNECTING, StoreConnectivity.DELAY_DISCONNECT),
            store.getState(),
        )
    }

    @Test
    fun connectingAfterSustainedOfflineUsesTheShortDelay() {
        var now = 0L
        val store = StoreConnectivity(Dispatcher(TestScheduler()), Clock { now }, ObservationDeck())
        store.handleDeviceNetworkStateUpdated(NetworkMonitor.State.OFFLINE_AIRPLANE_MODE)
        now = 2_000
        store.handleDeviceNetworkStateUpdated(NetworkMonitor.State.ONLINE)
        assertEquals(
            StoreConnectivity.DelayedState(StoreConnectivity.State.CONNECTING, StoreConnectivity.DELAY_NOMINAL),
            store.getState(),
        )
    }

    @Test
    fun backgroundingPausesTheRemainingDelay() {
        var now = 0L
        val store = StoreConnectivity(Dispatcher(TestScheduler()), Clock { now }, ObservationDeck())
        store.handleDeviceNetworkStateUpdated(NetworkMonitor.State.OFFLINE)
        now = 400
        store.handleBackgrounded(true)
        now = 60_000
        store.handleBackgrounded(false)
        assertEquals(600L, store.getState().delay)
    }
}
