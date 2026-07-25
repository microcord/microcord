package com.discord.stores

import com.discord.models.message.Message
import com.discord.stores.StoreMessagesLoader.Direction
import com.discord.stores.StoreMessagesLoader.LoadRequest
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.schedulers.TestScheduler

class MessageHistoryStoreTest {
    @Before
    fun initializePersistence() {
        Persister.init(Files.createTempDirectory("message-history").toFile(), Observable.never())
    }

    @Test
    fun loadPaginateAndJumpUseFiftyMessageRequests() {
        val requests = mutableListOf<LoadRequest>()
        val loader = StoreMessagesLoader(loader = { request, callback ->
            requests += request
            val start = when (request.direction) {
                Direction.INITIAL -> 101L
                Direction.BEFORE -> 51L
                Direction.AFTER -> 151L
                Direction.AROUND -> requireNotNull(request.anchorMessageId) - 24
            }
            callback(Result.success((start until start + request.limit).map(::message)))
        })
        val chunks = mutableListOf<StoreMessagesLoader.ChannelChunk>()
        val scrolls = mutableListOf<Long>()
        loader.observeChunks().subscribe(chunks::add)
        loader.observeScrollTo().subscribe(scrolls::add)
        loader.handleAuthToken("test-token")
        loader.handleConnected(true)

        loader.handleChannelSelected(7)
        loader.loadBefore(7, 101)
        loader.loadAfter(7, 150)
        loader.jumpToMessage(7, 500)

        assertEquals(listOf(Direction.INITIAL, Direction.BEFORE, Direction.AFTER, Direction.AROUND), requests.map { it.direction })
        assertTrue(requests.all { it.limit == 50 })
        assertEquals(4, chunks.size)
        assertTrue(chunks.last().isJump)
        assertEquals(listOf(500L), scrolls)
    }

    @Test
    fun loadFailurePublishesRetryStateAndCanRetry() {
        var attempts = 0
        val loader = StoreMessagesLoader(loader = { _, callback ->
            attempts++
            if (attempts == 1) callback(Result.failure(IllegalStateException("offline")))
            else callback(Result.success(emptyList()))
        })
        val states = mutableListOf<StoreMessagesLoader.ChannelLoadedState>()
        loader.getMessagesLoadedState(9).subscribe(states::add)
        loader.handleAuthToken("test-token")
        loader.handleConnected(true)
        loader.handleChannelSelected(9)

        assertNotNull(states.last().lastError)
        assertEquals(3_000L, states.last().retryDelayMillis)
        loader.retry()
        assertEquals(2, attempts)
        assertEquals(1_500L, states.last().retryDelayMillis)
    }

    @Test
    fun holderTrimsAtThresholdDetachesAndPublishesImmutableSnapshots() {
        val holder = StoreMessagesHolder(cache = Persister("trim-${System.nanoTime()}", emptyMap()))
        holder.selectChannel(1)
        holder.loadMessageChunks(listOf(StoreMessagesLoader.ChannelChunk(
            channelId = 1,
            messages = (1L..200L).map(::message),
            isInitial = true,
            isPresent = false,
            isAppendingTop = true,
            isJump = false,
        )))

        assertEquals((1L..100L).toList(), holder.getMessages(1).map(Message::id))
        assertEquals(setOf(1L), holder.getDetached())
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (holder.getAll() as MutableMap<Long, List<Message>>)[2] = emptyList()
        }
    }

    @Test
    fun partialUpdateAndDeleteMutateOnlyTheTargetMessage() {
        val fixture = storeFixture()
        fixture.dispatcher.schedule {
            fixture.store.handleChannelSelected(3)
            fixture.store.handleMessageCreate(3, message(10, "before"))
            fixture.store.handleMessageCreate(3, message(11, "untouched"))
        }
        fixture.scheduler.triggerActions()
        fixture.dispatcher.schedule {
            fixture.store.handleMessageUpdate(3, 10) { it.copy(content = "after") }
            fixture.store.handleMessageDelete(3, 11)
        }
        fixture.scheduler.triggerActions()

        assertEquals(listOf(10L), fixture.store.getMessages(3).map(Message::id))
        assertEquals("after", fixture.store.getMessage(3, 10)?.content)
    }

    @Test
    fun nonceReplacementRemovesOptimisticMessageExactlyOnce() {
        val fixture = storeFixture()
        fixture.dispatcher.schedule {
            fixture.store.handleChannelSelected(4)
            fixture.store.handleLocalMessageCreate(4, "nonce", message(100, "optimistic"))
        }
        fixture.scheduler.triggerActions()
        fixture.dispatcher.schedule {
            fixture.store.handleMessageCreate(4, message(200, "server"), "nonce")
            fixture.store.handleMessageCreate(4, message(200, "server"), "nonce")
        }
        fixture.scheduler.triggerActions()

        assertEquals(listOf(200L), fixture.store.getMessages(4).map(Message::id))
        assertEquals(null, fixture.store.localMessagesHolder.getMessage(4, "nonce"))
    }

    @Test
    fun cachesRestoreAndLogoutClearsBothHistories() {
        val syncedKey = "synced-${System.nanoTime()}"
        val localKey = "local-${System.nanoTime()}"
        val first = storeFixture(syncedKey, localKey)
        first.dispatcher.schedule {
            first.store.handleChannelSelected(5)
            first.store.handleMessageCreate(5, message(300, "cached"))
            first.store.handleLocalMessageCreate(5, "pending", message(301, "pending"))
        }
        first.scheduler.triggerActions()
        Persister.persistAll()

        val restored = storeFixture(syncedKey, localKey)
        restored.dispatcher.schedule {
            restored.store.restore()
            restored.store.handleChannelSelected(5)
        }
        restored.scheduler.triggerActions()
        assertEquals(listOf(300L, 301L), restored.store.getMessages(5).map(Message::id))

        restored.dispatcher.schedule { restored.store.handlePreLogout() }
        restored.scheduler.triggerActions()
        assertTrue(restored.store.getMessages(5).isEmpty())
        assertTrue(restored.store.localMessagesHolder.getFlattenedMessages().isEmpty())
        Persister.persistAll()

        val afterLogout = storeFixture(syncedKey, localKey)
        afterLogout.dispatcher.schedule {
            afterLogout.store.restore()
            afterLogout.store.handleChannelSelected(5)
        }
        afterLogout.scheduler.triggerActions()
        assertTrue(afterLogout.store.getMessages(5).isEmpty())
    }

    @Test
    fun mostRecentOnlyMovesForwardAndClearsOnLogout() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreMessagesMostRecent(deck, Persister("recent-${System.nanoTime()}", emptyMap()))
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        dispatcher.schedule {
            store.handleMessageCreate(8, 20)
            store.handleMessageCreate(8, 19)
        }
        scheduler.triggerActions()
        assertEquals(20L, store.getMostRecentIds()[8])
        dispatcher.schedule { store.handlePreLogout() }
        scheduler.triggerActions()
        assertFalse(store.getMostRecentIds().containsKey(8))
    }

    private fun storeFixture(
        syncedKey: String = "synced-${System.nanoTime()}",
        localKey: String = "local-${System.nanoTime()}",
    ): Fixture {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreMessages(
            deck,
            StoreMessagesHolder(Persister(syncedKey, emptyMap())),
            StoreLocalMessagesHolder(Persister(localKey, emptyMap())),
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        return Fixture(scheduler, dispatcher, store)
    }

    private fun message(id: Long, content: String = "message-$id") = Message(id = id, content = content)

    private data class Fixture(
        val scheduler: TestScheduler,
        val dispatcher: Dispatcher,
        val store: StoreMessages,
    )
}
