package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.forum.ForumUnreads
import com.discord.api.message.Message as ApiMessage
import com.discord.api.message.reaction.MessageReactionUpdate
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelMessageDelete
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.lang.reflect.Modifier
import java.nio.file.Files
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test
import rx.schedulers.TestScheduler

/** Behavioral coverage for the forum-post stores and the archived-thread paging state machine. */
class ForumThreadStoresTest {

    @Test
    fun `only a post's opening message is retained and reactions are folded into it`() {
        val fixture = fixture()
        fixture.dispatch { users.handleConnectionOpen(readyPayload()) }

        // A forum post's opening message shares the post's id; anything else in the post is ignored.
        fixture.dispatch {
            forumPostMessages.handleMessageCreate(apiMessage(id = 500, channelId = 31))
            forumPostMessages.handleMessageCreate(apiMessage(id = 31, channelId = 31, content = "opening"))
        }
        assertEquals("opening", fixture.forumPostMessages.getForumPostFirstMessage(31)?.content)

        fixture.dispatch { forumPostMessages.handleMessageUpdate(apiMessage(31, 31, content = "edited")) }
        assertEquals("edited", fixture.forumPostMessages.getForumPostFirstMessage(31)?.content)

        // Somebody else's reaction lands with me=false, mine flips the flag and bumps the count.
        fixture.dispatch { forumPostMessages.handleReactionAdd(reaction(31, 31, userId = 8, name = "wave")) }
        assertEquals(1, fixture.forumPostMessages.getForumPostFirstMessage(31)?.reactions?.single()?.count)
        assertEquals(false, fixture.forumPostMessages.getForumPostFirstMessage(31)?.reactions?.single()?.me)

        fixture.dispatch { forumPostMessages.handleReactionAdd(reaction(31, 31, userId = 7, name = "wave")) }
        val mine = fixture.forumPostMessages.getForumPostFirstMessage(31)?.reactions?.single()
        assertEquals(2, mine?.count)
        assertEquals(true, mine?.me)

        // Removing the last of a reaction drops the entry entirely rather than leaving a zero count.
        fixture.dispatch {
            forumPostMessages.handleReactionRemove(reaction(31, 31, userId = 7, name = "wave"))
            forumPostMessages.handleReactionRemove(reaction(31, 31, userId = 8, name = "wave"))
        }
        assertEquals(emptyList<Any>(), fixture.forumPostMessages.getForumPostFirstMessage(31)?.reactions)

        // Deleting the opening message removes the whole post body.
        fixture.dispatch { forumPostMessages.handleMessageDelete(ModelMessageDelete(31, 31)) }
        assertNull(fixture.forumPostMessages.getForumPostFirstMessage(31))
    }

    @Test
    fun `forum unread counts follow the gateway and local acks`() {
        val fixture = fixture()
        fixture.dispatch {
            users.handleConnectionOpen(readyPayload())
            channels.handleConnectionOpen(readyPayload())
            threadsActive.handleConnectionOpen()
            forumPostReadStates.handleConnectionOpen(readyPayload())
        }

        // READY read states record which channels have ever been opened.
        assertEquals(setOf(31L), fixture.forumPostReadStates.getThreadIdsWithPersistedReadStates())

        fixture.dispatch { forumPostReadStates.handleForumUnreads(forumUnreads(permissionDenied = false, 31L to 4)) }
        assertEquals(mapOf(31L to 4), fixture.forumPostReadStates.getThreadUnreadCounts())

        // A permission-denied response must not clobber the counts already held.
        fixture.dispatch { forumPostReadStates.handleForumUnreads(forumUnreads(permissionDenied = true, 31L to 0)) }
        assertEquals(mapOf(31L to 4), fixture.forumPostReadStates.getThreadUnreadCounts())

        // Someone else's message increments; my own marks the post read.
        fixture.dispatch { forumPostReadStates.handleMessageCreate(apiMessage(600, 31, authorId = 8)) }
        assertEquals(mapOf(31L to 5), fixture.forumPostReadStates.getThreadUnreadCounts())
        fixture.dispatch { forumPostReadStates.handleMessageCreate(apiMessage(601, 31, authorId = 7)) }
        assertEquals(mapOf(31L to 0), fixture.forumPostReadStates.getThreadUnreadCounts())

        // A message in a thread whose parent is not a forum channel is not a forum post.
        fixture.dispatch { forumPostReadStates.handleMessageCreate(apiMessage(602, 11, authorId = 8)) }
        assertEquals(mapOf(31L to 0), fixture.forumPostReadStates.getThreadUnreadCounts())

        // Archiving the post retires its counter.
        fixture.dispatch { forumPostReadStates.handleThreadCreateOrUpdate(thread(31, parentId = 30, archived = true)) }
        assertEquals(emptyMap<Long, Int>(), fixture.forumPostReadStates.getThreadUnreadCounts())
    }

    @Test
    fun `forum unread requests are gated, deduplicated and carry each post's ack`() {
        val fixture = fixture()
        fixture.dispatch {
            users.handleConnectionOpen(readyPayload())
            guilds.handleConnectionOpen(readyPayload())
            channels.handleConnectionOpen(readyPayload())
            threadsActive.handleConnectionOpen()
            threadsJoined.handleConnectionOpen(readyPayload())
            threadsActiveJoined.handleConnectionOpen()
            messagesMostRecent.handleConnectionOpen(readyPayload())
            messageAck.handleConnectionOpen(readyPayload())
        }

        fixture.forumPostReadStates.requestForumUnreads(guildId = 1, channelId = 30)
        fixture.scheduler.triggerActions()
        assertEquals(1, fixture.gatewayRequests.size)
        val (guildId, channelId, pairs) = fixture.gatewayRequests.single()
        assertEquals(1L, guildId)
        assertEquals(30L, channelId)
        // Only post 31 lives under channel 30, and it is paired with its acked message id.
        assertEquals(listOf(31L to 900L), pairs)

        // A second request for the same channel on the same connection is suppressed.
        fixture.forumPostReadStates.requestForumUnreads(guildId = 1, channelId = 30)
        fixture.scheduler.triggerActions()
        assertEquals(1, fixture.gatewayRequests.size)

        // Reconnecting clears the suppression set.
        fixture.dispatch { forumPostReadStates.handleConnectionOpen(readyPayload()) }
        fixture.forumPostReadStates.requestForumUnreads(guildId = 1, channelId = 30)
        fixture.scheduler.triggerActions()
        assertEquals(2, fixture.gatewayRequests.size)
    }

    @Test
    fun `archived thread paging derives its cursor from the tail of the pages already held`() {
        val first = thread(101, parentId = 30, archived = true, archiveTimestamp = "2022-03-01T00:00:00")
        val second = thread(102, parentId = 30, archived = true, archiveTimestamp = "2022-02-01T00:00:00")

        assertNull(ArchivedThreadsStore.ThreadListingType.getLastArchiveTimestamp(emptyList()))
        assertEquals(
            "2022-02-01T00:00:00",
            ArchivedThreadsStore.ThreadListingType.getLastArchiveTimestamp(listOf(first, second)),
        )
        // Paging from an unknown position would duplicate rows forever, so it fails loudly instead.
        assertThrows(IllegalStateException::class.java) {
            ArchivedThreadsStore.ThreadListingType.getLastArchiveTimestamp(
                listOf(thread(103, parentId = 30, archived = true)),
            )
        }
    }

    @Test
    fun `an archived thread listing fails cleanly and resets on reconnect`() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val forumPostMessages = StoreForumPostMessages(dispatcher, StoreUser(dispatcher = dispatcher, observationDeck = deck), deck) { null }
        val store = ArchivedThreadsStore(dispatcher, forumPostMessages, deck, { null }, {})
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store, forumPostMessages), deck))

        val observed = mutableListOf<ArchivedThreadsStore.ThreadListingState>()
        var terminated = 0
        store.loadAndObserveThreadListing(1, ArchivedThreadsStore.ThreadListingType.MY_ARCHIVED_PRIVATE_THREADS)
            .subscribe { observed += it }
        scheduler.triggerActions()

        assertEquals(ArchivedThreadsStore.ThreadListingState.Uninitialized, observed.first())
        assertEquals(ArchivedThreadsStore.ThreadListingState.Error, observed.last())

        store.fetchListing(1, ArchivedThreadsStore.ThreadListingType.MY_ARCHIVED_PRIVATE_THREADS) { terminated++ }
        scheduler.triggerActions()
        assertEquals(1, terminated)

        // A reconnect drops every listing back to Uninitialized.
        dispatcher.schedule { store.handleConnectionOpen() }
        scheduler.triggerActions()
        assertEquals(ArchivedThreadsStore.ThreadListingState.Uninitialized, observed.last())
    }

    // -- Fixture --------------------------------------------------------------------------------

    private class Fixture {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val gatewayRequests = mutableListOf<Triple<Long, Long, List<Pair<Long, Long>>>>()
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        val channels = StoreChannels(dispatcher, deck)
        val threadsActive = StoreThreadsActive(channels, deck)
        val threadsJoined = StoreThreadsJoined(users, deck)
        val threadsActiveJoined = StoreThreadsActiveJoined(threadsActive, threadsJoined, deck)
        val messages = StoreMessages(deck)
        val messagesMostRecent = StoreMessagesMostRecent(deck, storeChannels = channels)
        val messageAck = StoreMessageAck(
            dispatcher, deck, channels, messages, messagesMostRecent, threadsActiveJoined,
            threadsActive, guilds, Clock { 1_700_000_000_000L }, { users.getMeInternal().id },
            { _, _, _, _ -> },
        )
        val forumPostMessages = StoreForumPostMessages(dispatcher, users, deck) { null }
        val forumPostReadStates = StoreForumPostReadStates(
            dispatcher = dispatcher,
            requestForumUnreadsOverGateway = { guildId, channelId, pairs ->
                gatewayRequests += Triple(guildId, channelId, pairs)
                true
            },
            storeThreadsActive = threadsActive,
            storeMessageAck = messageAck,
            storeChannels = channels,
            storeUser = users,
            observationDeck = deck,
        )

        init {
            dispatcher.registerDispatchHandlers(
                StoreV2DispatchHandler(
                    listOf(
                        users, guilds, channels, threadsActive, threadsJoined, threadsActiveJoined,
                        messages, messagesMostRecent, messageAck, forumPostMessages, forumPostReadStates,
                    ),
                    deck,
                ),
            )
        }

        fun dispatch(block: Fixture.() -> Unit) {
            dispatcher.schedule { block() }
            scheduler.triggerActions()
        }
    }

    private fun fixture() = Fixture()

    // -- Payload builders -----------------------------------------------------------------------

    private fun readyPayload(): ModelPayload = gson.fromJson(
        """
        {
          "user": {"id":7,"username":"me","discriminator":"0000"},
          "private_channels": [],
          "read_state": {"version":1,"partial":false,"entries":[{"id":31,"last_message_id":900,"mention_count":0,"version":1}]},
          "guilds": [$GUILD_JSON]
        }
        """.trimIndent(),
        ModelPayload::class.java,
    )

    private fun thread(
        id: Long,
        parentId: Long,
        archived: Boolean = false,
        archiveTimestamp: String? = null,
    ): Channel {
        val quotedTimestamp = archiveTimestamp?.let { "\"$it\"" } ?: "null"
        return gson.fromJson(
            """{"id":$id,"type":11,"guild_id":1,"name":"t$id","parent_id":$parentId,
               "thread_metadata":{"archived":$archived,"auto_archive_duration":1440,"locked":false,
               "archive_timestamp":$quotedTimestamp}}""",
            Channel::class.java,
        )
    }

    private fun apiMessage(
        id: Long,
        channelId: Long,
        content: String = "",
        authorId: Long = 7,
    ): ApiMessage = gson.fromJson(
        """{"id":$id,"channel_id":$channelId,"type":0,"content":"$content",
           "author":{"id":$authorId,"username":"u$authorId"}}""",
        ApiMessage::class.java,
    )

    private fun reaction(channelId: Long, messageId: Long, userId: Long, name: String): MessageReactionUpdate =
        gson.fromJson(
            """{"user_id":$userId,"channel_id":$channelId,"message_id":$messageId,
               "emoji":{"id":null,"name":"$name","animated":false}}""",
            MessageReactionUpdate::class.java,
        )

    private fun forumUnreads(permissionDenied: Boolean, vararg counts: Pair<Long, Int>): ForumUnreads = gson.fromJson(
        """{"channel_id":30,"permission_denied":$permissionDenied,
           "threads":[${counts.joinToString(",") { """{"thread_id":${it.first},"missing":false,"count":${it.second}}""" }}]}""",
        ForumUnreads::class.java,
    )

    companion object {
        /** Guild 1: text channel 10 with thread 11, forum channel 30 with post 31. */
        private const val GUILD_JSON = """
        {
          "id": 1, "name": "Guild", "owner_id": 7, "unavailable": false, "features": [],
          "members": [{"guild_id":1,"user":{"id":7,"username":"me","discriminator":"0000"},"roles":[],"pending":false}],
          "channels": [
            {"id":10,"type":0,"guild_id":1,"name":"general"},
            {"id":30,"type":15,"guild_id":1,"name":"forum"}
          ],
          "threads": [
            {"id":11,"type":11,"guild_id":1,"name":"live","parent_id":10,
             "thread_metadata":{"archived":false,"auto_archive_duration":1440,"locked":false}},
            {"id":31,"type":11,"guild_id":1,"name":"post","parent_id":30,
             "thread_metadata":{"archived":false,"auto_archive_duration":1440,"locked":false}}
          ]
        }
        """

        private val gson = GsonBuilder()
            .excludeFieldsWithModifiers(Modifier.STATIC)
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()

        private val directory = Files.createTempDirectory("forum-thread-stores")

        @JvmStatic
        @BeforeClass
        fun initializePersister() = Persister.init(directory.toFile())

        @JvmStatic
        @AfterClass
        fun cleanUp() {
            directory.toFile().deleteRecursively()
        }
    }
}
