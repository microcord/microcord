package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.message.Message as ApiMessage
import com.discord.api.thread.ThreadMemberListUpdate
import com.discord.api.thread.ThreadMemberUpdate
import com.discord.api.thread.ThreadMembersUpdate
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.models.message.Message
import com.discord.models.thread.dto.ModelThreadListSync
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.lang.reflect.Modifier
import java.nio.file.Files
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rx.schedulers.TestScheduler

/**
 * Behavioral coverage for the thread store family. Each test drives the stores exactly as
 * `StoreStream` will: through `Dispatcher.schedule`, with a `StoreV2DispatchHandler` producing the
 * post-dispatch snapshots the public getters read.
 */
class ThreadStoresTest {

    @Test
    fun `joined threads seed from ready and follow membership events`() {
        val fixture = fixture()
        fixture.dispatch {
            users.handleConnectionOpen(readyPayload())
            threadsJoined.handleConnectionOpen(readyPayload())
        }

        // Only threads carrying a `member` for the signed-in user are recorded.
        assertEquals(setOf(11L), fixture.threadsJoined.getAllJoinedThreads().keys)
        assertEquals(1, fixture.threadsJoined.getJoinedThread(11)?.flags)
        assertTrue(fixture.threadsJoined.hasJoined(11))
        assertFalse(fixture.threadsJoined.hasJoined(12))

        // A membership update for somebody else must not create a row.
        fixture.dispatch { threadsJoined.handleThreadMemberUpdate(threadMemberUpdate(threadId = 12, userId = 99)) }
        assertFalse(fixture.threadsJoined.hasJoined(12))

        fixture.dispatch { threadsJoined.handleThreadMemberUpdate(threadMemberUpdate(threadId = 12, userId = 7)) }
        assertTrue(fixture.threadsJoined.hasJoined(12))

        // Being removed from a thread drops the row; other members' removals do not.
        fixture.dispatch { threadsJoined.handleThreadMembersUpdate(threadMembersRemoval(threadId = 12, removed = 99)) }
        assertTrue(fixture.threadsJoined.hasJoined(12))
        fixture.dispatch { threadsJoined.handleThreadMembersUpdate(threadMembersRemoval(threadId = 12, removed = 7)) }
        assertFalse(fixture.threadsJoined.hasJoined(12))

        // Leaving the guild prunes everything under it.
        fixture.dispatch { threadsJoined.handleGuildDelete(1) }
        assertEquals(emptyMap<Long, StoreThreadsJoined.JoinedThread>(), fixture.threadsJoined.getAllJoinedThreads())
    }

    @Test
    fun `active threads exclude archived ones and index forum posts separately`() {
        val fixture = fixture()
        fixture.dispatch {
            channels.handleConnectionOpen(readyPayload())
            threadsActive.handleConnectionOpen()
        }

        // 11 is a live thread under a text channel, 31 is a live post under a forum channel,
        // 12 is archived and must not appear at all.
        assertEquals(setOf(11L, 31L), fixture.threadsActive.getAllActiveThreadsById().keys)
        assertEquals(setOf(31L), fixture.threadsActive.getAllForumThreadsById().keys)

        // Archiving a live thread removes it from every index.
        fixture.dispatch { threadsActive.handleThreadCreateOrUpdate(thread(31, parentId = 30, archived = true)) }
        assertEquals(setOf(11L), fixture.threadsActive.getAllActiveThreadsById().keys)
        assertEquals(emptySet<Long>(), fixture.threadsActive.getAllForumThreadsById().keys)

        // THREAD_LIST_SYNC records the guild as synced so the browser can tell empty from unloaded.
        val synced = mutableListOf<Boolean>()
        fixture.threadsActive.observeIsThreadSyncedGuild(1).subscribe { synced += it }
        assertEquals(listOf(false), synced)
        fixture.dispatch {
            threadsActive.handleThreadListSync(ModelThreadListSync(1, listOf(thread(13, parentId = 10))))
        }
        assertEquals(listOf(false, true), synced)
        assertEquals(setOf(11L, 13L), fixture.threadsActive.getAllActiveThreadsById().keys)

        fixture.dispatch { threadsActive.handleGuildDelete(1) }
        assertEquals(emptySet<Long>(), fixture.threadsActive.getAllActiveThreadsById().keys)
    }

    @Test
    fun `active joined threads require both membership and an unarchived channel`() {
        val fixture = fixture()
        fixture.dispatch {
            users.handleConnectionOpen(readyPayload())
            channels.handleConnectionOpen(readyPayload())
            threadsActive.handleConnectionOpen()
            threadsJoined.handleConnectionOpen(readyPayload())
            threadsActiveJoined.handleConnectionOpen()
        }

        // 11 is both active and joined; 31 is active but not joined.
        assertEquals(setOf(11L), fixture.threadsActiveJoined.getAllActiveJoinedThreadsById().keys)

        val forGuild = mutableListOf<Map<Long, Map<Long, StoreThreadsActiveJoined.ActiveJoinedThread>>>()
        fixture.threadsActiveJoined.observeActiveJoinedThreadsForGuild(1).subscribe { forGuild += it }
        assertEquals(mapOf(10L to setOf(11L)), forGuild.last().mapValues { it.value.keys })

        // Joining an already-active thread adds it without any channel event.
        fixture.dispatch {
            threadsJoined.handleThreadMemberUpdate(threadMemberUpdate(threadId = 31, userId = 7))
            threadsActiveJoined.handleThreadMemberUpdate(threadMemberUpdate(threadId = 31, userId = 7))
        }
        assertEquals(setOf(11L, 31L), fixture.threadsActiveJoined.getAllActiveJoinedThreadsById().keys)

        // Archiving the thread removes the joined projection even though membership survives.
        val archived = thread(31, parentId = 30, archived = true)
        fixture.dispatch {
            threadsActive.handleThreadCreateOrUpdate(archived)
            threadsActiveJoined.handleThreadCreateOrUpdateOrDelete(archived)
        }
        assertTrue(fixture.threadsJoined.hasJoined(31))
        assertEquals(setOf(11L), fixture.threadsActiveJoined.getAllActiveJoinedThreadsById().keys)
    }

    @Test
    fun `thread member rosters only exist once the thread has been subscribed to`() {
        val fixture = fixture()
        fixture.dispatch { channels.handleConnectionOpen(readyPayload()) }

        // A patch for an unsubscribed thread is dropped rather than fabricating a partial roster.
        fixture.dispatch { threadMembers.handleThreadMembersUpdate(threadMembersRemoval(11, removed = 7)) }
        assertEquals(emptySet<Long>(), fixture.threadMembers.getThreadMembers(11))

        fixture.dispatch { threadMembers.handleThreadMemberListUpdate(memberListUpdate(11, 7, 8)) }
        assertEquals(setOf(7L, 8L), fixture.threadMembers.getThreadMembers(11))

        // An update naming a thread the channel store cannot place is ignored entirely.
        fixture.dispatch { threadMembers.handleThreadMemberListUpdate(memberListUpdate(999, 7)) }
        assertEquals(emptySet<Long>(), fixture.threadMembers.getThreadMembers(999))

        fixture.dispatch { threadMembers.handleThreadMembersUpdate(threadMembersRemoval(11, removed = 8)) }
        assertEquals(setOf(7L), fixture.threadMembers.getThreadMembers(11))

        // Archiving tears the roster down; the server stops sending updates for it.
        fixture.dispatch { threadMembers.handleThreadCreateOrUpdate(thread(11, parentId = 10, archived = true)) }
        assertEquals(emptySet<Long>(), fixture.threadMembers.getThreadMembers(11))
    }

    @Test
    fun `thread message counts cap at the maximum and exclude the starter message`() {
        val fixture = fixture()
        fixture.dispatch {
            channels.handleConnectionOpen(readyPayload())
            threadMessages.handleConnectionOpen(readyPayload())
        }

        val states = mutableListOf<Map<Long, StoreThreadMessages.ThreadState>>()
        fixture.threadMessages.observeThreadCountAndLatestMessage().subscribe { states += it }

        // `message_count` from the READY payload seeds the counter.
        assertEquals(3, fixture.threadMessages.getThreadMessageCount(11))

        fixture.dispatch { threadMessages.handleMessageCreate(apiMessage(id = 500, channelId = 11)) }
        assertEquals(4, fixture.threadMessages.getThreadMessageCount(11))

        // A loaded page recomputes the count, dropping the mirrored parent message (type 21), and
        // refuses to use that same message as the preview excerpt.
        fixture.dispatch {
            threadMessages.handleMessagesLoaded(
                StoreMessagesLoader.ChannelChunk(
                    channelId = 11,
                    messages = listOf(message(400, 11, type = 21), message(399, 11)),
                    isInitial = true,
                    isPresent = true,
                    isAppendingTop = false,
                    isJump = false,
                ),
            )
        }
        assertEquals(1, fixture.threadMessages.getThreadMessageCount(11))
        assertNull(states.last()[11]?.mostRecentMessage)

        // The counter never exceeds MAX_THREAD_MESSAGE_COUNT no matter how many creates arrive.
        fixture.dispatch {
            repeat(StoreThreadMessages.MAX_THREAD_MESSAGE_COUNT + 10) { index ->
                threadMessages.handleMessageCreate(apiMessage(id = 1_000L + index, channelId = 11))
            }
        }
        assertEquals(StoreThreadMessages.MAX_THREAD_MESSAGE_COUNT, fixture.threadMessages.getThreadMessageCount(11))

        // Deleting the parent channel takes every thread under it.
        fixture.dispatch { threadMessages.handleChannelDelete(channelStub(10)) }
        assertNull(fixture.threadMessages.getThreadMessageCount(11))
    }

    @Test
    fun `thread draft latches into sending until it is cleared`() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val store = StoreThreadDraft(dispatcher)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), ObservationDeck()))
        val observed = mutableListOf<StoreThreadDraft.ThreadDraftState>()
        store.observeDraftState().subscribe { observed += it }

        store.setDraftState(StoreThreadDraft.ThreadDraftState(isPrivate = true, threadName = "topic"))
        scheduler.triggerActions()
        store.setDraftSending()
        scheduler.triggerActions()

        assertEquals("topic", observed.last().threadName)
        assertTrue(observed.last().isPrivate)
        assertTrue(observed.last().isSending)

        store.clearDraftState()
        scheduler.triggerActions()
        assertEquals(StoreThreadDraft.ThreadDraftState(), observed.last())
    }

    // -- Fixture --------------------------------------------------------------------------------

    private class Fixture {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val channels = StoreChannels(dispatcher, deck)
        val threadsJoined = StoreThreadsJoined(users, deck)
        val threadsActive = StoreThreadsActive(channels, deck)
        val threadsActiveJoined = StoreThreadsActiveJoined(threadsActive, threadsJoined, deck)
        val threadMembers = StoreThreadMembers(deck, channels)
        val messages = StoreMessages(deck)
        val threadMessages = StoreThreadMessages(messages, channels, deck)

        init {
            dispatcher.registerDispatchHandlers(
                StoreV2DispatchHandler(
                    listOf(
                        users, channels, threadsJoined, threadsActive,
                        threadsActiveJoined, threadMembers, messages, threadMessages,
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
          "guilds": [$GUILD_JSON]
        }
        """.trimIndent(),
        ModelPayload::class.java,
    )

    private fun thread(id: Long, parentId: Long, archived: Boolean = false): Channel = gson.fromJson(
        """{"id":$id,"type":11,"guild_id":1,"name":"t$id","parent_id":$parentId,
           "thread_metadata":{"archived":$archived,"auto_archive_duration":1440,"locked":false}}""",
        Channel::class.java,
    )

    private fun channelStub(id: Long): Channel = gson.fromJson(
        """{"id":$id,"type":0,"guild_id":1,"name":"c$id"}""",
        Channel::class.java,
    )

    private fun threadMemberUpdate(threadId: Long, userId: Long): ThreadMemberUpdate = gson.fromJson(
        """{"id":$threadId,"guild_id":1,"user_id":$userId,"flags":2,
           "join_timestamp":"2022-01-01T00:00:00","muted":false}""",
        ThreadMemberUpdate::class.java,
    )

    private fun threadMembersRemoval(threadId: Long, removed: Long): ThreadMembersUpdate = gson.fromJson(
        """{"id":$threadId,"guild_id":1,"member_count":1,"removed_member_ids":[$removed]}""",
        ThreadMembersUpdate::class.java,
    )

    private fun memberListUpdate(threadId: Long, vararg userIds: Long): ThreadMemberListUpdate = gson.fromJson(
        """{"guild_id":1,"thread_id":$threadId,
           "members":[${userIds.joinToString(",") { "{\"user_id\":$it}" }}]}""",
        ThreadMemberListUpdate::class.java,
    )

    private fun apiMessage(id: Long, channelId: Long): ApiMessage = gson.fromJson(
        """{"id":$id,"channel_id":$channelId,"type":0}""",
        ApiMessage::class.java,
    )

    private fun message(id: Long, channelId: Long, type: Int = 0) =
        Message(id = id, channelId = channelId, type = type)

    companion object {
        /**
         * Guild 1 holds a text channel (10) with a live thread (11, joined, three messages) and an
         * archived thread (12), plus a forum channel (30) with one live post (31, not joined).
         */
        private const val GUILD_JSON = """
        {
          "id": 1, "name": "Guild", "owner_id": 7, "unavailable": false, "features": [],
          "members": [{"guild_id":1,"user":{"id":7,"username":"me","discriminator":"0000"},"roles":[],"pending":false}],
          "channels": [
            {"id":10,"type":0,"guild_id":1,"name":"general"},
            {"id":30,"type":15,"guild_id":1,"name":"forum"}
          ],
          "threads": [
            {"id":11,"type":11,"guild_id":1,"name":"live","parent_id":10,"message_count":3,
             "thread_metadata":{"archived":false,"auto_archive_duration":1440,"locked":false},
             "member":{"id":11,"user_id":7,"flags":1,"join_timestamp":"2022-01-01T00:00:00","muted":false}},
            {"id":12,"type":11,"guild_id":1,"name":"archived","parent_id":10,
             "thread_metadata":{"archived":true,"auto_archive_duration":1440,"locked":false}},
            {"id":31,"type":11,"guild_id":1,"name":"post","parent_id":30,
             "thread_metadata":{"archived":false,"auto_archive_duration":1440,"locked":false}}
          ]
        }
        """

        /**
         * Only `STATIC` is excluded, matching the gateway parser: `Channel.member`,
         * `Channel.memberCount` and friends are `@Transient` yet the gateway really sends them, so
         * the default `TRANSIENT` exclusion would drop the very fields these stores read.
         */
        private val gson = GsonBuilder()
            .excludeFieldsWithModifiers(Modifier.STATIC)
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()

        private val directory = Files.createTempDirectory("thread-stores")

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
