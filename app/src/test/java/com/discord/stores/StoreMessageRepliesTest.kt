package com.discord.stores

import com.discord.api.message.MessageReference
import com.discord.models.domain.ModelMessageDelete
import com.discord.models.message.Message
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.schedulers.TestScheduler

/** Referenced-message resolution for inline reply previews. */
class StoreMessageRepliesTest {

    @Before
    fun initializePersistence() {
        Persister.init(Files.createTempDirectory("replies").toFile(), Observable.never())
    }

    /** The server inlines the referenced message on most replies; that is the fast path. */
    @Test
    fun inlinedReferencedMessageResolvesImmediately() {
        val fixture = fixture()
        fixture.dispatcher.schedule {
            fixture.store.handleMessageCreate(reply(id = 20, referencedId = 10, inlineReferenced = true))
        }
        fixture.scheduler.triggerActions()

        val state = fixture.store.getAllMessageReferences()[10L]
        assertTrue(state is StoreMessageReplies.MessageState.Loaded)
        assertEquals(10L, (state as StoreMessageReplies.MessageState.Loaded).message.id)
    }

    /**
     * With no inlined body and nothing in the message store, the target is recorded as unloaded so
     * the preview renders a placeholder rather than collapsing the row.
     */
    @Test
    fun missingReferenceIsRecordedAsUnloaded() {
        val fixture = fixture()
        fixture.dispatcher.schedule {
            fixture.store.handleMessageCreate(reply(id = 21, referencedId = 11, inlineReferenced = false))
        }
        fixture.scheduler.triggerActions()

        assertEquals(
            StoreMessageReplies.MessageState.Unloaded,
            fixture.store.getAllMessageReferences()[11L],
        )
    }

    /** Once the target arrives through normal history, the placeholder upgrades to loaded. */
    @Test
    fun laterArrivalOfTheTargetUpgradesTheState() {
        val fixture = fixture()
        fixture.dispatcher.schedule {
            fixture.store.handleMessageCreate(reply(id = 22, referencedId = 12, inlineReferenced = false))
            fixture.store.handleLoadMessages(listOf(Message(id = 12, channelId = CHANNEL, content = "original")))
        }
        fixture.scheduler.triggerActions()

        val state = fixture.store.getAllMessageReferences()[12L]
        assertEquals("original", (state as StoreMessageReplies.MessageState.Loaded).message.content)
    }

    @Test
    fun deletingTheTargetMarksItDeleted() {
        val fixture = fixture()
        fixture.dispatcher.schedule {
            fixture.store.handleMessageCreate(reply(id = 23, referencedId = 13, inlineReferenced = true))
            fixture.store.handleMessageDelete(ModelMessageDelete(CHANNEL, listOf(13L)))
        }
        fixture.scheduler.triggerActions()

        assertEquals(
            StoreMessageReplies.MessageState.Deleted,
            fixture.store.getAllMessageReferences()[13L],
        )
    }

    /** The per-channel view is what the chat list observes. */
    @Test
    fun referencesAreIndexedByChannel() {
        val fixture = fixture()
        fixture.dispatcher.schedule {
            fixture.store.handleMessageCreate(reply(id = 24, referencedId = 14, inlineReferenced = true))
        }
        fixture.scheduler.triggerActions()

        val perChannel = mutableListOf<Map<Long, StoreMessageReplies.MessageState>>()
        fixture.store.observeMessageReferencesForChannel(CHANNEL).subscribe(perChannel::add)
        assertTrue(perChannel.last().containsKey(14L))
        assertTrue(fixture.store.getAllMessageReferences().containsKey(14L))
    }

    private fun reply(id: Long, referencedId: Long, inlineReferenced: Boolean) =
        com.discord.api.message.Message(
            id = id,
            channelId = CHANNEL,
            content = "reply",
            type = MESSAGE_TYPE_REPLY,
            messageReference = MessageReference(null, CHANNEL, referencedId),
            referencedMessage = if (inlineReferenced) {
                com.discord.api.message.Message(id = referencedId, channelId = CHANNEL, content = "original")
            } else {
                null
            },
        )

    private fun fixture(): Fixture {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val messages = StoreMessages(
            deck,
            StoreMessagesHolder(Persister("replies-synced-${System.nanoTime()}", emptyMap())),
            StoreLocalMessagesHolder(Persister("replies-local-${System.nanoTime()}", emptyMap())),
        )
        val store = StoreMessageReplies(dispatcher, messages, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(messages, store), deck))
        return Fixture(scheduler, dispatcher, store)
    }

    private data class Fixture(
        val scheduler: TestScheduler,
        val dispatcher: Dispatcher,
        val store: StoreMessageReplies,
    )

    private companion object {
        const val CHANNEL = 55L
        const val MESSAGE_TYPE_REPLY = 19
    }
}
