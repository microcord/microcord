package com.discord.stores

import com.discord.models.domain.ModelApplicationStream
import com.discord.models.domain.StreamCreateOrUpdate
import com.discord.models.domain.StreamDelete
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreApplicationStreamingTest {

    @Test
    fun watchingASecondStreamKeepsTheFirst() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val users = StoreUser(
            dispatcher = dispatcher,
            observationDeck = deck,
            meCache = Persister("streaming-${System.nanoTime()}", StoreUser.EMPTY_ME_USER),
        )
        val watches = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        val store = StoreApplicationStreaming(
            dispatcher,
            users,
            getSelectedVoiceChannelId = { CHANNEL_ID },
            streamWatchRequest = { watches += it },
            streamDeleteRequest = { deletes += it.streamKey },
            observationDeck = deck,
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(users, store), deck))
        val first = ModelApplicationStream.GuildStream(GUILD_ID, CHANNEL_ID, 2L).encodedStreamKey
        val second = ModelApplicationStream.GuildStream(GUILD_ID, CHANNEL_ID, 3L).encodedStreamKey
        fun active() = store.observeActiveStreams().toBlocking().first()

        store.targetStream(first)
        dispatcher.schedule { store.handleStreamCreate(StreamCreateOrUpdate(first)) }
        store.targetStream(second, keepOthers = true)
        dispatcher.schedule { store.handleStreamCreate(StreamCreateOrUpdate(second)) }
        scheduler.triggerActions()

        assertEquals(listOf(first, second), watches)
        assertTrue(deletes.isEmpty())
        assertEquals(listOf(first, second), active().keys.toList())
        // Callers that show one stream get the one asked for most recently.
        assertEquals(second, store.observeActiveStream().toBlocking().first()?.stream?.encodedStreamKey)

        dispatcher.schedule {
            store.handleStreamDelete(StreamDelete(first, StreamDelete.Reason.USER_REQUESTED))
            store.handleStreamDelete(StreamDelete(second))
        }
        scheduler.triggerActions()

        assertEquals(setOf(second), active().keys)
        assertEquals(StoreApplicationStreaming.ActiveApplicationStream.State.ENDED, active().getValue(second).state)

        dispatcher.schedule { store.handleVoiceChannelSelected(0L) }
        scheduler.triggerActions()

        assertTrue(active().isEmpty())
    }

    @Test
    fun plainWatchReplacesTheStreamBeingWatched() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val users = StoreUser(
            dispatcher = dispatcher,
            observationDeck = deck,
            meCache = Persister("streaming-replace-${System.nanoTime()}", StoreUser.EMPTY_ME_USER),
        )
        val watches = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        val store = StoreApplicationStreaming(
            dispatcher,
            users,
            getSelectedVoiceChannelId = { CHANNEL_ID },
            streamWatchRequest = { watches += it },
            streamDeleteRequest = { deletes += it.streamKey },
            observationDeck = deck,
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(users, store), deck))
        val first = ModelApplicationStream.GuildStream(GUILD_ID, CHANNEL_ID, 2L).encodedStreamKey
        val second = ModelApplicationStream.GuildStream(GUILD_ID, CHANNEL_ID, 3L).encodedStreamKey

        store.targetStream(first)
        dispatcher.schedule { store.handleStreamCreate(StreamCreateOrUpdate(first)) }
        store.targetStream(second)
        scheduler.triggerActions()

        assertEquals(listOf(first, second), watches)
        assertEquals(listOf(first), deletes)
    }

    private companion object {
        private const val GUILD_ID = 5L
        private const val CHANNEL_ID = 50L

        @JvmStatic
        @BeforeClass
        fun initializePersister() {
            Persister.init(Files.createTempDirectory("streaming-test").toFile())
        }
    }
}
