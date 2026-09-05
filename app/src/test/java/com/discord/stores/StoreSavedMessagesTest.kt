package com.discord.stores

import com.discord.api.message.Message
import com.discord.api.savedmessage.SaveData
import com.discord.api.savedmessage.SavedMessage
import com.discord.api.savedmessage.SavedMessageDelete
import com.discord.api.savedmessage.SavedMessagesResponse
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import rx.observers.TestSubscriber
import rx.schedulers.Schedulers
import rx.schedulers.TestScheduler
import rx.subjects.PublishSubject

class StoreSavedMessagesTest {
    @Test
    fun gatewayCreatesAndRestDeletesAreIdempotentAndPublishImmutableSnapshots() {
        val f = Fixture()
        f.loaded()
        val observed = mutableListOf<StoreSavedMessages.State>()
        f.store.observe().subscribe(observed::add)
        val saved = saved(1)
        assertTrue(f.store.getState().entries.isEmpty())
        f.dispatch { f.store.handleSavedMessageCreate(saved) }
        val before = f.store.getState()
        val emissionCount = observed.size
        f.dispatch { f.store.handleSavedMessageCreate(saved) }
        assertEquals(emissionCount, observed.size)
        assertEquals(1, f.fetches.size)
        assertTrue(f.store.isSaved(10, 1))
        assertTrue(f.store.isBookmarked(10, 1))

        val removed = TestSubscriber<Void>()
        f.store.remove(10, 1).subscribe(removed)
        f.removals.single().onCompleted()
        f.scheduler.triggerActions()
        removed.assertCompleted()
        f.dispatch { f.store.handleSavedMessageDelete(SavedMessageDelete(10, 1)) }
        assertFalse(f.store.isSaved(10, 1))
        assertEquals(saved, before.entries["10-1"])
        try {
            (before.entries as MutableMap<String, SavedMessage>).clear()
            fail("Published entries must be immutable")
        } catch (_: UnsupportedOperationException) {
            assertEquals(1, before.entries.size)
        }
    }

    @Test
    fun failedRemovalRetainsEntries() {
        val f = Fixture()
        f.loaded(saved(1))

        val failure = IllegalStateException("fixture failure")
        val failed = TestSubscriber<Void>()
        f.store.remove(10, 1).subscribe(failed)
        f.removals.single().onError(failure)
        f.scheduler.triggerActions()
        failed.assertError(failure)
        assertTrue(f.store.isBookmarked(10, 1))
    }

    @Test
    fun bookmarksAreNewestSavedFirstAndRemindersRemainInTheSameIndex() {
        val f = Fixture()
        f.loaded(saved(1), saved(3, dueAt = 1), saved(2))
        assertEquals(listOf(2L, 1L), f.store.getState().bookmarks.map { it.saveData.messageId })
        assertTrue(f.store.isSaved(10, 3))
        assertFalse(f.store.isBookmarked(10, 3))
        assertEquals(1, f.store.getOverdueMessageReminderCount())
        f.dispatch { f.store.handleSavedMessageCreate(saved(2, dueAt = Long.MAX_VALUE)) }
        assertEquals(listOf(1L), f.store.getState().bookmarks.map { it.saveData.messageId })
        assertEquals(3, f.store.getState().entries.size)
    }

    @Test
    fun partialMessageUpdateAndDeletionRetainSaveMetadata() {
        val f = Fixture()
        val saved = saved(1)
        f.loaded(saved)
        val before = f.store.getState()
        f.dispatch { f.store.handleMessageUpdate(Message(1, 10, content = "edited", pinned = true)) }
        val updated = f.store.getState().entries.getValue("10-1")
        val previousMessage = checkNotNull(saved.message)
        val updatedMessage = checkNotNull(updated.message)
        assertEquals(saved.saveData, updated.saveData)
        assertEquals("edited", updatedMessage.content)
        assertEquals(previousMessage.author, updatedMessage.author)
        assertEquals(previousMessage.timestamp, updatedMessage.timestamp)
        assertEquals(true, updatedMessage.hit)
        assertEquals("fixture message", before.entries.getValue("10-1").message!!.content)

        f.dispatch { f.store.handleMessageDelete(10, listOf(1, 99)) }
        assertNull(f.store.getState().entries.getValue("10-1").message)
        assertEquals(saved.saveData, f.store.getState().entries.getValue("10-1").saveData)
        f.dispatch { f.store.handleMessageUpdate(Message(1, 10, content = "late edit")) }
        assertNull(f.store.getState().entries.getValue("10-1").message)
        assertTrue(f.store.isBookmarked(10, 1))
    }

    @Test
    fun fetchFailureRetainsEntriesAndRetryIsDeduplicated() {
        val f = Fixture()
        assertTrue(f.store.getState().loading)
        f.loaded(saved(1))
        assertFalse(f.store.getIsStale())
        f.ready(7)
        val failure = IllegalStateException("fixture fetch failure")
        f.fetches.last().onError(failure)
        f.scheduler.triggerActions()
        assertEquals(saved(1), f.store.getState().entries["10-1"])
        assertSame(failure, f.store.getState().error)
        assertFalse(f.store.getState().loading)
        assertTrue(f.store.getIsStale())

        repeat(3) { f.store.fetch() }
        f.scheduler.triggerActions()
        assertEquals(3, f.fetches.size)
        f.loaded(saved(2))
        assertEquals(setOf("10-2"), f.store.getState().entries.keys)
        assertNull(f.store.getState().error)
        f.store.fetch()
        f.scheduler.triggerActions()
        assertEquals(3, f.fetches.size)
    }

    @Test
    fun staleFullListCannotDropCreatesOrResurrectDeletedEntries() {
        val f = Fixture()
        f.loaded(saved(1))
        f.ready(7)
        f.dispatch {
            f.store.handleSavedMessageCreate(saved(2))
            f.store.handleSavedMessageDelete(SavedMessageDelete(10, 1))
            f.store.handleSavedMessageDelete(SavedMessageDelete(10, 3))
        }
        f.loaded(saved(1), saved(3))
        assertEquals(setOf("10-2"), f.store.getState().entries.keys)
        assertEquals(2, f.fetches.size)
        assertFalse(f.store.getState().loading)
        assertFalse(f.store.getIsStale())
    }

    @Test
    fun unrelatedMessageTrafficDoesNotRetryTheSavedCollectionFetch() {
        val f = Fixture()
        f.loaded(saved(1))
        f.ready(7)
        f.dispatch {
            repeat(5000) { index ->
                // Many events for the same IDs must coalesce, not grow an event list.
                val id = 1000L + index % 100
                f.store.handleMessageUpdate(Message(id, 99, content = "unrelated fixture $index"))
                f.store.handleMessageDelete(99, listOf(id + 100))
            }
        }
        f.loaded(saved(1))
        assertEquals(2, f.fetches.size)
        assertEquals(saved(1), f.store.getState().entries["10-1"])
        assertFalse(f.store.getState().loading)
        assertFalse(f.store.getIsStale())
        assertNull(f.store.getState().error)
    }

    @Test
    fun inFlightJournalKeepsRelevantPartialEditsDeletesAndNewSaves() {
        val f = Fixture()
        f.loaded(saved(1), saved(4))
        f.ready(7)
        f.dispatch {
            f.store.handleMessageUpdate(Message(1, 10, content = "edited fixture"))
            f.store.handleMessageUpdate(Message(1, 10, pinned = true))
            f.store.handleMessageDelete(10, listOf(4))
            f.store.handleMessageUpdate(Message(4, 10, content = "late edit must not revive"))
            f.store.handleSavedMessageCreate(saved(2))
            f.store.handleMessageUpdate(Message(2, 10, content = "new save edited"))
            f.store.handleSavedMessageDelete(SavedMessageDelete(10, 3))
        }
        f.loaded(saved(1), saved(3), saved(4))
        val entries = f.store.getState().entries
        assertEquals(setOf("10-1", "10-2", "10-4"), entries.keys)
        val edited = checkNotNull(entries.getValue("10-1").message)
        assertEquals("edited fixture", edited.content)
        assertEquals(true, edited.pinned)
        assertEquals(saved(1).message!!.author, edited.author)
        assertEquals(saved(1).saveData, entries.getValue("10-1").saveData)
        assertEquals("new save edited", entries.getValue("10-2").message!!.content)
        assertNull(entries.getValue("10-4").message)
        assertEquals(saved(4).saveData, entries.getValue("10-4").saveData)
        assertEquals(2, f.fetches.size)
        assertFalse(f.store.getState().loading)
    }

    @Test
    fun delayedRestDeleteDoesNotUndoANewerGatewaySave() {
        val f = Fixture()
        f.loaded(saved(1))
        f.store.remove(10, 1).subscribe()
        val newer = saved(1).let { it.copy(saveData = it.saveData.copy(savedAt = UtcDateTime(2000))) }
        f.dispatch { f.store.handleSavedMessageCreate(newer) }
        f.removals.single().onCompleted()
        f.scheduler.triggerActions()
        assertEquals(newer, f.store.getState().entries["10-1"])
        assertEquals(2, f.fetches.size)
        f.loaded(newer)
        assertEquals(newer, f.store.getState().entries["10-1"])
    }

    @Test
    fun messageDeletionDuringFirstFetchCannotRestoreOldMessageContent() {
        val f = Fixture()
        f.dispatch { f.store.handleMessageDelete(10, listOf(1)) }
        f.loaded(saved(1))
        assertEquals(1, f.fetches.size)
        assertFalse(f.store.getState().loading)
        assertEquals(saved(1).saveData, f.store.getState().entries.getValue("10-1").saveData)
        assertNull(f.store.getState().entries.getValue("10-1").message)
    }

    @Test
    fun firstFetchMergesUnknownEditsAndRespectsLaterExplicitSaveEvents() {
        val f = Fixture()
        f.dispatch {
            f.store.handleMessageUpdate(Message(1, 10, content = "first edit"))
            f.store.handleMessageUpdate(Message(1, 10, pinned = true))
            f.store.handleMessageDelete(10, listOf(2))
            f.store.handleSavedMessageCreate(saved(2))
            f.store.handleSavedMessageCreate(saved(3))
            f.store.handleSavedMessageDelete(SavedMessageDelete(10, 3))
            f.store.handleSavedMessageDelete(SavedMessageDelete(10, 4))
            f.store.handleSavedMessageCreate(saved(4))
        }
        f.loaded(saved(1), saved(3))
        val entries = f.store.getState().entries
        assertEquals(setOf("10-1", "10-2", "10-4"), entries.keys)
        assertEquals("first edit", entries.getValue("10-1").message!!.content)
        assertEquals(true, entries.getValue("10-1").message!!.pinned)
        assertEquals(saved(2), entries["10-2"])
        assertEquals(saved(4), entries["10-4"])
        assertEquals(1, f.fetches.size)
    }

    @Test
    fun journalOverflowPreservesCurrentDataWithoutAnAutomaticRetryLoop() {
        val f = Fixture()
        f.loaded(saved(1))
        f.ready(7)
        val cancelledFetch = f.fetches.last()
        f.dispatch {
            repeat(1025) { index ->
                f.store.handleMessageUpdate(Message(1000L + index, 99, content = "overflow fixture"))
            }
        }
        assertEquals(2, f.fetches.size)
        assertEquals(saved(1), f.store.getState().entries["10-1"])
        assertFalse(f.store.getState().loading)
        assertTrue(f.store.getIsStale())
        assertNotNull(f.store.getState().error)
        cancelledFetch.onNext(SavedMessagesResponse(listOf(saved(99))))
        cancelledFetch.onCompleted()
        f.scheduler.triggerActions()
        assertEquals(setOf("10-1"), f.store.getState().entries.keys)

        f.store.fetch()
        f.scheduler.triggerActions()
        f.loaded(saved(2))
        assertEquals(3, f.fetches.size)
        assertEquals(setOf("10-2"), f.store.getState().entries.keys)
        assertNull(f.store.getState().error)
    }

    @Test
    fun failedFetchDiscardsItsUnknownMessagePatchJournal() {
        val f = Fixture()
        f.dispatch { f.store.handleMessageUpdate(Message(1, 10, content = "obsolete fixture")) }
        f.fetches.single().onError(IllegalStateException("fixture fetch failure"))
        f.scheduler.triggerActions()
        f.store.fetch()
        f.scheduler.triggerActions()
        f.loaded(saved(1))
        assertEquals(saved(1), f.store.getState().entries["10-1"])
        assertEquals(2, f.fetches.size)
    }

    @Test
    fun unavailableEntriesRefetchOnlyForRelevantChanges() {
        val f = Fixture()
        f.loaded(saved(1).copy(message = null))
        f.store.invalidateUnavailable(20)
        f.scheduler.triggerActions()
        assertEquals(1, f.fetches.size)
        f.store.invalidateUnavailable(10)
        f.scheduler.triggerActions()
        assertEquals(2, f.fetches.size)
        f.loaded(saved(1))
        f.store.invalidateUnavailable()
        f.scheduler.triggerActions()
        assertEquals(2, f.fetches.size)
    }

    @Test
    fun logoutClearsAllStateAndOldRequestsCannotAffectTheNextAccount() {
        val f = Fixture()
        f.loaded(saved(1), saved(2, dueAt = 1).copy(message = null))
        val deferredRemove = f.store.remove(10, 50)
        val oldRemove = TestSubscriber<Void>()
        f.store.remove(10, 1).subscribe(oldRemove)
        f.ready(7)
        val oldFetch = f.fetches.last()
        f.dispatch { f.store.handleMessageUpdate(Message(1, 10, content = "old account pending edit")) }
        f.dispatch { f.store.handlePreLogout() }
        assertTrue(f.store.getState().entries.isEmpty())
        assertFalse(f.store.getState().loading)
        assertNull(f.store.getState().error)
        assertEquals(0L, f.store.getLastChanged())
        assertEquals(0, f.store.getOverdueMessageReminderCount())

        f.ready(8)
        f.loaded(saved(1).copy(message = Message(1, 10, content = "new account fixture")))
        f.removals.single().onCompleted()
        oldFetch.onNext(SavedMessagesResponse(listOf(saved(99))))
        oldFetch.onCompleted()
        f.scheduler.triggerActions()
        assertEquals(setOf("10-1"), f.store.getState().entries.keys)
        assertEquals("new account fixture", f.store.getState().entries.getValue("10-1").message!!.content)
        val deferred = TestSubscriber<Void>()
        deferredRemove.subscribe(deferred)
        deferred.assertError(CancellationException::class.java)
        assertEquals(1, f.removals.size)
        f.store.invalidateUnavailable()
        f.scheduler.triggerActions()
        assertEquals(3, f.fetches.size)
    }

    @Test
    fun queuedFetchFailureIsIgnoredAfterAccountChange() {
        val f = Fixture()
        val oldFetch = f.fetches.single()
        f.dispatcher.schedule {
            f.store.handlePreLogout()
            f.store.handleConnectionOpen(8)
        }
        oldFetch.onError(IllegalStateException("old account fixture"))
        f.scheduler.triggerActions()
        f.loaded(saved(2))
        assertNull(f.store.getState().error)
        assertEquals(setOf("10-2"), f.store.getState().entries.keys)
    }

    @Test
    fun oldAccountRemovalErrorsAreNotDeliveredToTheNextAccount() {
        val f = Fixture()
        f.loaded(saved(1))
        val remove = TestSubscriber<Void>()
        f.store.remove(10, 1).subscribe(remove)
        f.dispatch { f.store.handlePreLogout() }
        f.ready(8)
        f.removals.single().onError(IllegalStateException("old remove fixture"))
        f.loaded(saved(3))
        remove.assertNoErrors()
        assertEquals(setOf("10-3"), f.store.getState().entries.keys)
    }

    private class Fixture {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        private val deck = ObservationDeck()
        val fetches = mutableListOf<PublishSubject<SavedMessagesResponse>>()
        val removals = mutableListOf<PublishSubject<Void>>()
        private val api = Proxy.newProxyInstance(
            RestAPIInterface::class.java.classLoader,
            arrayOf(RestAPIInterface::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSavedMessages" -> PublishSubject.create<SavedMessagesResponse>().also(fetches::add)
                "deleteSavedMessage" -> PublishSubject.create<Void>().also(removals::add)
                else -> error("Unexpected REST call: ${method.name}")
            }
        } as RestAPIInterface
        val store = StoreSavedMessages(dispatcher, deck, { api }, requestScheduler = Schedulers.immediate())

        init {
            dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
            ready(7)
        }

        fun dispatch(action: () -> Unit) {
            dispatcher.schedule(action)
            scheduler.triggerActions()
        }

        fun ready(userId: Long) = dispatch { store.handleConnectionOpen(userId) }

        fun loaded(vararg entries: SavedMessage) {
            val response = fetches.last()
            response.onNext(SavedMessagesResponse(entries.toList()))
            response.onCompleted()
            scheduler.triggerActions()
        }
    }

    private fun saved(id: Long, dueAt: Long? = null) = SavedMessage(
        Message(id, 10, author = User(7, "fixture"), content = "fixture message", timestamp = UtcDateTime(500), hit = true),
        SaveData(10, id, UtcDateTime(id * 1000), "author fixture", "channel fixture", "message fixture", null, 7, "note fixture", dueAt?.let(::UtcDateTime)),
    )
}
