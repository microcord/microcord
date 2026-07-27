package com.discord.stores

import android.util.Log
import com.discord.app.AppLog
import com.discord.stores.updates.ObservationDeck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * The off-dispatcher guard *reports*; it does not throw.
     *
     * `StoreV2.assertStoreThread` in the reference is
     * `Logger.e(AppLog, "markChanged() should be run from the Store Dispatcher", IllegalStateException(...))`
     * — verified in the Vineflower decompile of `com/discord/stores/StoreV2`. Throwing here would
     * crash the process on any off-thread `markChanged`, so the contract this pins is that the
     * mutation still lands and an error is logged with the offending stack.
     */
    @Test
    fun mutationIsReportedOutsideDispatcherAndRxDisconnects() {
        // Rebind the store thread to a thread that is not this one, so the guard trips regardless of
        // what any previously executed test left in StoreThreadScheduler.
        StoreThreadScheduler.newThread {}
        val store = TestStore()

        val reported = reportedErrors { store.value = 1 }

        assertEquals(1, reported.size)
        assertEquals("markChanged() should be run from the Store Dispatcher", reported.single().message)
        assertTrue(reported.single().throwable is IllegalStateException)
        assertEquals(1, store.valueForTest)

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

    /**
     * Captures what [com.discord.app.AppLog] recorded while [block] ran.
     *
     * `AppLog` is inert until the application initialises it, so the minimum priority has to be
     * lowered for the duration and restored afterwards.
     *
     * The sink has to be installed too. Without it `AppLog` falls through to logcat, where
     * `Log.getStackTraceString` returns `null` under `unitTests.isReturnDefaultValues` and the
     * non-null `println(priority, message)` parameter then throws NPE — so every error carrying a
     * throwable failed the test rather than being recorded. That is exactly what `AppLog.sink`
     * exists for.
     */
    private fun reportedErrors(block: () -> Unit): List<AppLog.LoggedItem> {
        val reported = mutableListOf<AppLog.LoggedItem>()
        val previousPriority = AppLog.minimumPriority
        val previousSink = AppLog.sink
        AppLog.minimumPriority = Log.ERROR
        AppLog.sink = { _, _, _, _ -> }
        val subscription = AppLog.observeLogs().subscribe { reported += it }
        try {
            block()
        } finally {
            subscription.unsubscribe()
            AppLog.minimumPriority = previousPriority
            AppLog.sink = previousSink
        }
        return reported
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
