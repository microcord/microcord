package com.discord.stores

import com.discord.api.message.Message
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.nio.file.Files
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreMessageAckTest {
    @Before
    fun resetPersisters() = Persister.reset()

    @Test
    fun `auto ack requires loaded attached bottom state`() {
        assertTrue(StoreMessageAck.shouldAutoAck(true, true, false, false, false))
        assertFalse(StoreMessageAck.shouldAutoAck(false, true, false, false, false))
        assertFalse(StoreMessageAck.shouldAutoAck(true, false, false, false, false))
        assertFalse(StoreMessageAck.shouldAutoAck(true, true, true, false, false))
        assertFalse(StoreMessageAck.shouldAutoAck(true, true, false, true, false))
        assertFalse(StoreMessageAck.shouldAutoAck(true, true, false, false, true))
    }

    @Test
    fun `full ready replaces persisted acks while partial ready overlays`() {
        persistAcks(mapOf(10L to ack(100), DM_ID to ack(110)))
        val full = Fixture()
        full.ready(payload(partial = false, states = mapOf(10L to 200L)))
        assertEquals(mapOf(10L to ack(200)), full.ackStore.getAll())

        persistAcks(mapOf(10L to ack(100), DM_ID to ack(110)))
        val partial = Fixture()
        partial.ready(payload(partial = true, states = mapOf(10L to 200L)))
        assertEquals(mapOf(10L to ack(200), DM_ID to ack(110)), partial.ackStore.getAll())
    }

    @Test
    fun `full ready does not recreate an absent read dm ack`() {
        persistAcks(mapOf(DM_ID to ack(100)))
        val fixture = Fixture()
        fixture.ready(payload(partial = false, states = emptyMap()))

        assertNull(fixture.ackStore.getForChannel(DM_ID))
    }

    @Test
    fun `channel selection clears only the mark unread lock`() {
        persistAcks(mapOf(10L to StoreMessageAck.Ack(100, viewed = true, isLockedAck = true)))
        val fixture = Fixture()

        fixture.dispatch { ackStore.handleChannelSelected() }

        assertEquals(StoreMessageAck.Ack(100, viewed = true, isLockedAck = false), fixture.ackStore.getForChannel(10))
    }

    @Test
    fun `own message advances the authoritative ledger`() {
        val fixture = Fixture()
        fixture.ready(payload(partial = false, states = mapOf(10L to 100L)))

        fixture.dispatch { ackStore.handleMessageCreate(message(300, 10, ME_ID)) }

        assertEquals(ack(300), fixture.ackStore.getForChannel(10))
    }

    @Test
    fun `auto ack is local first and posts non manual zero mention body`() {
        val fixture = Fixture()
        fixture.ready(payload(partial = false, states = mapOf(10L to 100L), lastMessageId = 300L))

        fixture.ackStore.ack(10, isLockedAck = false)
        fixture.await { fixture.ackStore.getForChannel(10)?.messageId == 300L }

        assertEquals(StoreMessageAck.Ack(300, viewed = true, isLockedAck = false), fixture.ackStore.getForChannel(10))
        assertEquals(listOf(AckRequest(10, 300, manual = false, mentionCount = 0)), fixture.requests)
    }

    @Test
    fun `mark unread posts exact manual and mention count fallback`() {
        val fixture = Fixture(markUnreadMentionCount = 2)
        fixture.ready(payload(partial = false, states = mapOf(10L to 300L), lastMessageId = 300L))
        fixture.dispatch {
            messages.handleChannelSelected(10)
            messages.handleMessageCreate(com.discord.models.message.Message(message(300, 10, ME_ID)))
        }

        fixture.ackStore.markUnread(10, 300)
        fixture.await { fixture.requests.isNotEmpty() }

        val request = fixture.requests.single()
        assertEquals(10L, request.channelId)
        assertTrue(request.messageId < 300L)
        assertTrue(request.manual)
        assertEquals(2, request.mentionCount)
        assertTrue(requireNotNull(fixture.ackStore.getForChannel(10)).isLockedAck)
    }

    @Test
    fun `write only most recent cache is not restored`() {
        val cache = Persister<Map<Long, Long>>(StoreMessagesMostRecent.MOST_RECENT_MESSAGE_IDS, emptyMap())
        cache.set(mapOf(10L to 999L), persist = true)

        val store = StoreMessagesMostRecent(ObservationDeck(), cache)

        assertEquals(emptyMap<Long, Long>(), store.getMostRecentIds())
    }

    @Test
    fun `account isolation clears the live and persisted ack ledger`() {
        persistAcks(mapOf(10L to ack(100)))
        val fixture = Fixture()

        fixture.dispatch {
            ackStore.handlePreLogout()
            ackStore.clearForAccountIsolation()
        }

        assertEquals(emptyMap<Long, StoreMessageAck.Ack>(), fixture.ackStore.getAll())
        assertEquals(emptyMap<Long, StoreMessageAck.Ack>(), Fixture().ackStore.getAll())
    }

    private class Fixture(markUnreadMentionCount: Int = 0) {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val requests = mutableListOf<AckRequest>()
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        val channels = StoreChannels(dispatcher, deck)
        val threadsActive = StoreThreadsActive(channels, deck)
        val threadsJoined = StoreThreadsJoined(users, deck)
        val threadsActiveJoined = StoreThreadsActiveJoined(threadsActive, threadsJoined, deck)
        val messages = StoreMessages(deck)
        val mostRecent = StoreMessagesMostRecent(deck, storeChannels = channels)
        val ackStore = StoreMessageAck(
            dispatcher,
            deck,
            channels,
            messages,
            mostRecent,
            threadsActiveJoined,
            threadsActive,
            guilds,
            Clock { NOW },
            meId = { ME_ID },
            ackRequest = { channelId, messageId, manual, mentionCount ->
                requests += AckRequest(channelId, messageId, manual, mentionCount)
            },
            processMarkUnread = { _, _ -> markUnreadMentionCount },
        )

        init {
            dispatcher.registerDispatchHandlers(
                StoreV2DispatchHandler(
                    listOf(users, guilds, channels, threadsActive, threadsJoined, threadsActiveJoined, messages, mostRecent, ackStore),
                    deck,
                ),
            )
        }

        fun ready(payload: ModelPayload) = dispatch {
            guilds.handleConnectionOpen(payload)
            channels.handleConnectionOpen(payload)
            threadsActive.handleConnectionOpen()
            threadsJoined.handleConnectionOpen(payload)
            threadsActiveJoined.handleConnectionOpen()
            mostRecent.handleConnectionOpen(payload)
            ackStore.handleConnectionOpen(payload)
        }

        fun dispatch(block: Fixture.() -> Unit) {
            dispatcher.schedule { block() }
            scheduler.triggerActions()
        }

        fun await(condition: () -> Boolean) {
            repeat(100) {
                scheduler.triggerActions()
                if (condition()) return
                Thread.sleep(10)
            }
            assertTrue("Timed out waiting for the ACK pipeline", condition())
        }
    }

    data class AckRequest(val channelId: Long, val messageId: Long, val manual: Boolean, val mentionCount: Int)

    companion object {
        private const val ME_ID = 7L
        private const val DM_ID = 50L
        private const val NOW = 1_700_000_000_000L
        private val directory = Files.createTempDirectory("message-ack")
        private val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()

        @JvmStatic
        @BeforeClass
        fun initializePersister() = Persister.init(directory.toFile())

        @JvmStatic
        @AfterClass
        fun cleanUp() {
            directory.toFile().deleteRecursively()
        }

        private fun ack(messageId: Long) = StoreMessageAck.Ack(messageId, false, false)

        private fun persistAcks(acks: Map<Long, StoreMessageAck.Ack>) =
            Persister<Map<Long, StoreMessageAck.Ack>>(StoreMessageAck.ACKS_CACHE_KEY, emptyMap())
                .set(acks, persist = true)

        private fun payload(
            partial: Boolean,
            states: Map<Long, Long>,
            lastMessageId: Long = 300L,
        ): ModelPayload = gson.fromJson(
            """
            {
              "user":{"id":$ME_ID,"username":"me","discriminator":"0001"},
              "private_channels":[{"id":$DM_ID,"type":1,"last_message_id":$lastMessageId}],
              "read_state":{"version":3,"partial":$partial,"entries":[
                ${states.entries.joinToString(",") { "{\"id\":${it.key},\"last_message_id\":${it.value},\"mention_count\":0,\"version\":1}" }}
              ]},
              "guilds":[{
                "id":1,"name":"guild","owner_id":$ME_ID,"unavailable":false,"features":[],
                "members":[{"guild_id":1,"user":{"id":$ME_ID,"username":"me"},"roles":[],"joined_at":"2020-01-01T00:00:00.000Z"}],
                "channels":[{"id":10,"type":0,"guild_id":1,"name":"general","last_message_id":$lastMessageId}],
                "threads":[]
              }]
            }
            """.trimIndent(),
            ModelPayload::class.java,
        )

        private fun message(id: Long, channelId: Long, authorId: Long): Message = gson.fromJson(
            """{"id":$id,"channel_id":$channelId,"type":0,"content":"","author":{"id":$authorId,"username":"u"}}""",
            Message::class.java,
        )
    }
}
