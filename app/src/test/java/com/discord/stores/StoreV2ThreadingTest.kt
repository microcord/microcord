package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StoreV2ThreadingTest {
    private class TestStore : StoreV2() {
        val extraUpdate = object : ObservationDeck.UpdateSource {}
        var delegated: Int by MarkChangedDelegate(0)
        var overridden: String by MarkChangedDelegate("", OverrideUpdate)

        fun mark() = markChanged()
        fun mark(vararg updates: ObservationDeck.UpdateSource) = markChanged(*updates)
        fun unmark(updateSource: ObservationDeck.UpdateSource) = markUnchanged(updateSource)

        companion object {
            val OverrideUpdate = object : ObservationDeck.UpdateSource {}
        }
    }

    private val store = TestStore()

    @Before
    fun bindStoreThread() = StoreThreadScheduler.bindCurrentThread()

    @Test
    fun markChangedRecordsTheStoreAndItsExtraSources() {
        store.mark(store.extraUpdate)

        assertEquals(setOf(store, store.extraUpdate), store.updateSources)
    }

    @Test
    fun markUnchangedRemovesASingleSource() {
        store.mark(store.extraUpdate)

        store.unmark(store.extraUpdate)

        assertEquals(setOf<ObservationDeck.UpdateSource>(store), store.updateSources)
    }

    @Test
    fun dispatchEndClearsUpdateSources() {
        store.mark()

        store.onDispatchEnded()

        assertTrue(store.updateSources.isEmpty())
    }

    @Test
    fun delegateOnlyMarksChangedWhenTheValueDiffers() {
        store.delegated = 0
        assertTrue("an identical write must not dirty the store", store.updateSources.isEmpty())

        store.delegated = 1

        assertEquals(setOf<ObservationDeck.UpdateSource>(store), store.updateSources)
    }

    @Test
    fun delegateUsesItsOverrideUpdateSource() {
        store.overridden = "value"

        assertEquals(setOf(store, TestStore.OverrideUpdate), store.updateSources)
    }

    @Test
    fun markingChangedOffTheStoreThreadReportsButDoesNotThrow() {
        var failure: Throwable? = null
        val offThread = Thread { failure = runCatching { store.mark() }.exceptionOrNull() }
        offThread.start()
        offThread.join()

        // The store thread assertion must only report. Under JVM unit tests the android.util.Log
        // stub itself throws, so the guarantee we can assert here is that the failure is never
        // our own IllegalStateException from a hard `check`.
        assertFalse("off-thread markChanged must not fail the dispatch", failure is IllegalStateException)
    }

    @Test
    fun ownerlessDelegateWritesAssertNothing() {
        val delegate = StoreV2.MarkChangedDelegate(0)
        var failure: Throwable? = null

        val offThread = Thread {
            failure = runCatching { delegate.setValue(null, TestStore::delegated, 5) }.exceptionOrNull()
        }
        offThread.start()
        offThread.join()

        assertNull(failure)
        assertEquals(5, delegate.getValue(null, TestStore::delegated))
    }
}
