package com.discord.stores.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ObservationDeckTest {
    private val deck = ObservationDeck()
    private val source = object : ObservationDeck.UpdateSource {}
    private val otherSource = object : ObservationDeck.UpdateSource {}

    @Test
    fun onlyObserversWatchingAnUpdatedSourceAreNotified() {
        var watching = 0
        var notWatching = 0
        deck.connect(source, updateOnConnect = false) { watching++ }
        deck.connect(otherSource, updateOnConnect = false) { notWatching++ }

        deck.notify(setOf(source))

        assertEquals(1, watching)
        assertEquals(0, notWatching)
    }

    @Test
    fun disconnectedObserversAreDroppedOnTheNextNotify() {
        var updates = 0
        val observer = deck.connect(source, updateOnConnect = false) { updates++ }

        deck.disconnect(observer)
        deck.notify(setOf(source))
        deck.notify(setOf(source))

        assertEquals(0, updates)
    }

    @Test
    fun observerFailuresPropagateInsteadOfBeingSwallowed() {
        deck.connect(source, updateOnConnect = false) { throw IllegalStateException("observer bug") }

        try {
            deck.notify(setOf(source))
            fail("notify must not swallow an observer failure")
        } catch (expected: IllegalStateException) {
            assertEquals("observer bug", expected.message)
        }
    }

    @Test
    fun removingAStaleObserverStillNotifiesTheFollowingOne() {
        var updates = 0
        val stale = deck.connect(source, updateOnConnect = false) { }
        deck.connect(source, updateOnConnect = false) { updates++ }
        deck.disconnect(stale)

        deck.notify(setOf(source))

        assertEquals("the index must not skip the observer that shifted down", 1, updates)
    }

    @Test
    fun observersExposeADebugStringForErrorReporting() {
        val observer = deck.connect(source, updateOnConnect = false, observerName = "TestObserver") { }

        assertTrue(observer.toDebugLogString().startsWith("Observer name: TestObserver"))
    }
}
