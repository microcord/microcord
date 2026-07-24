package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import rx.schedulers.TestScheduler

class DispatcherStoreTest {
    @Test
    fun dispatchesSeriallyAndEndsAfterEachAction() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val events = mutableListOf<String>()
        dispatcher.registerDispatchHandlers(DispatchHandler { events += "ended" })

        dispatcher.schedule { events += "first" }
        dispatcher.schedule { events += "second" }
        assertEquals(emptyList<String>(), events)

        scheduler.triggerActions()
        assertEquals(listOf("first", "ended", "second", "ended"), events)
    }

    @Test
    fun storeThreadMatchesReferenceConfiguration() {
        val thread = StoreThreadScheduler.newThread {}

        assertEquals("Main-StoreThread", thread.name)
        assertEquals(8, thread.priority)
        assertEquals(thread.id, StoreThreadScheduler.storeThreadId)
    }

    @Test
    fun observersReceiveOneStableSnapshotAfterDispatch() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = TestStore()
        val observed = mutableListOf<List<Int>>()
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        deck.connect(store, updateOnConnect = false) { observed += store.snapshot }

        dispatcher.schedule {
            store.value = 1
            store.value = 2
        }
        scheduler.triggerActions()

        assertEquals(listOf(listOf(2)), observed)
        store.valueForTest = 3
        assertEquals(listOf(listOf(2)), observed)
    }

    @Test
    fun delegatedValuesTrackOverridesAndIgnoreEqualAssignments() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = TestStore()
        val overrideSource = object : ObservationDeck.UpdateSource {}
        val overriddenStore = OverrideStore(overrideSource)
        var storeUpdates = 0
        var overrideUpdates = 0
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store, overriddenStore), deck))
        deck.connect(store, updateOnConnect = false) { storeUpdates++ }
        deck.connect(overrideSource, updateOnConnect = false) { overrideUpdates++ }

        dispatcher.schedule {
            store.value = 0
            overriddenStore.value = 1
        }
        scheduler.triggerActions()

        assertEquals(0, storeUpdates)
        assertEquals(1, overrideUpdates)
    }

    @Test
    fun mutationIsRejectedOutsideDispatcherAndRxDisconnects() {
        val store = TestStore()
        assertThrows(IllegalStateException::class.java) { store.value = 1 }

        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val observed = mutableListOf<List<Int>>()
        val subscription = deck.connectRx(store, generator = { store.snapshot }).subscribe(observed::add)
        subscription.unsubscribe()
        dispatcher.schedule { store.value = 2 }
        scheduler.triggerActions()

        assertEquals(listOf(emptyList<Int>()), observed)
    }

    private class TestStore : StoreV2() {
        var value by MarkChangedDelegate(0)
        var snapshot: List<Int> = emptyList()
            private set

        var valueForTest: Int
            get() = value
            set(value) {
                snapshot = listOf(value)
            }

        override fun snapshotData() {
            snapshot = listOf(value)
        }
    }

    private class OverrideStore(source: ObservationDeck.UpdateSource) : StoreV2() {
        var value by MarkChangedDelegate(0, source)
    }
}
