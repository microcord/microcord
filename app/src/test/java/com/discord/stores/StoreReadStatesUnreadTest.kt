package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadState
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rx.schedulers.TestScheduler

/**
 * Regressions for the two defects that made read state silently wrong: mark-unread acking the
 * newest message instead of an older one, and thread/voice unreads being discarded because their
 * inputs were never populated.
 */
class StoreReadStatesUnreadTest {
    @Test
    fun readStateAndMessageAckCachesCannotShareTypedValues() {
        assertNotEquals(StoreMessageAck.ACKS_CACHE_KEY, StoreReadStates.ACKS_CACHE_KEY)
    }


    private val scheduler = TestScheduler()
    private val dispatcher = Dispatcher(scheduler)
    private val deck = ObservationDeck()

    private fun store(
        onAckRequest: (Long, Boolean, Boolean) -> Unit = { _, _, _ -> },
        loadedMessageIds: Map<Long, List<Long>> = emptyMap(),
    ): StoreReadStates {
        val store = StoreReadStates(dispatcher, { NOW }, deck, onAckRequest)
        store.bindMessageSource { channelId -> loadedMessageIds[channelId].orEmpty() }
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        return store
    }

    // -- mark unread ----------------------------------------------------------------------------

    @Test
    fun markUnreadAcksTheNewestMessageOlderThanTheTargetAndNeverAcksTheChannel() {
        val older = snowflake(1_600_000_000_000L)
        val target = snowflake(1_650_000_000_000L)
        val newest = snowflake(1_660_000_000_000L)
        val acked = mutableListOf<Long>()
        val store = store(
            onAckRequest = { channelId, _, _ -> acked += channelId },
            loadedMessageIds = mapOf(10L to listOf(older, target, newest)),
        )
        dispatcher.schedule { store.handleConnectionOpen(readPayload(false, ModelReadState(10, newest, 0, 1))) }
        scheduler.triggerActions()

        store.markUnread(10, target)
        scheduler.triggerActions()

        val ack = requireNotNull(store.getAck(10))
        assertEquals(older, ack.messageId)
        assertTrue(ack.isLockedAck)
        // The whole point of mark-unread: the channel must not be reported as fully read.
        assertEquals(emptyList<Long>(), acked)
    }

    @Test
    fun markUnreadWithoutLoadedHistoryFallsBackBelowTheTargetMessage() {
        val target = snowflake(1_650_000_000_000L)
        val store = store()
        store.markUnread(10, target)
        scheduler.triggerActions()

        val ack = requireNotNull(store.getAck(10))
        assertTrue("ack must be older than the target message", ack.messageId < target)
        assertTrue(ack.isLockedAck)
    }

    @Test
    fun markUnreadKeepsTheChannelUnreadAndSelectingAChannelReleasesTheLock() {
        val older = snowflake(1_600_000_000_000L)
        val target = snowflake(1_650_000_000_000L)
        val channel = channel(10, guildId = 20)
        val store = store(loadedMessageIds = mapOf(10L to listOf(older, target)))
        dispatcher.schedule {
            store.handleConnectionOpen(readPayload(false, ModelReadState(10, target, 0, 1)))
            store.updateUnreadInputs(
                StoreReadStates.Inputs(
                    channelPermissions = mapOf(10L to VIEW_CHANNEL),
                    channels = mapOf(10L to channel),
                    recentMessageIds = mapOf(10L to target),
                ),
            )
        }
        scheduler.triggerActions()
        assertEquals(emptySet<Long>(), store.getUnreadChannelIdsSnapshot())

        store.markUnread(10, target)
        scheduler.triggerActions()
        assertEquals(setOf(10L), store.getUnreadChannelIdsSnapshot())
        assertTrue(requireNotNull(store.getAck(10)).isLockedAck)

        dispatcher.schedule { store.handleChannelSelected() }
        scheduler.triggerActions()
        assertFalse(requireNotNull(store.getAck(10)).isLockedAck)
        // Releasing the lock must not move the cursor, so the channel stays unread.
        assertEquals(setOf(10L), store.getUnreadChannelIdsSnapshot())
    }

    // -- thread and voice unreads ---------------------------------------------------------------

    @Test
    fun joinedThreadsCountAsUnreadOnlyOnceThreadInputsArePopulated() {
        val oldMessage = snowflake(1_600_000_000_000L)
        val newMessage = snowflake(1_650_000_000_000L)
        val parent = channel(10, guildId = 20)
        val thread = channel(12, guildId = 0, type = Channel.PUBLIC_THREAD, parentId = 10)
        val store = store()

        dispatcher.schedule {
            store.handleConnectionOpen(readPayload(false, ModelReadState(12, oldMessage, 0, 1)))
            store.updateUnreadInputs(
                StoreReadStates.Inputs(
                    channelPermissions = mapOf(10L to VIEW_CHANNEL, 12L to VIEW_CHANNEL),
                    channels = mapOf(10L to parent, 12L to thread),
                    recentMessageIds = mapOf(12L to newMessage),
                    channelGuildIds = mapOf(12L to 20L),
                ),
            )
        }
        scheduler.triggerActions()
        // Not joined yet: a thread nobody joined must not raise an unread badge.
        assertEquals(emptySet<Long>(), store.getUnreadChannelIdsSnapshot())

        dispatcher.schedule {
            store.updateActiveThreads(
                activeJoinedThreads = mapOf(
                    12L to StoreThreadsActiveJoined.ActiveJoinedThread(thread, UtcDateTime(0), muted = false),
                ),
                activeForumThreads = emptyMap(),
            )
        }
        scheduler.triggerActions()
        assertEquals(setOf(12L), store.getUnreadChannelIdsSnapshot())
        assertEquals(setOf(20L), store.getUnreadGuildIdsSnapshot())
    }

    @Test
    fun activeForumPostsCountAsUnreadWithoutBeingJoined() {
        val oldMessage = snowflake(1_600_000_000_000L)
        val newMessage = snowflake(1_650_000_000_000L)
        val forum = channel(10, guildId = 20, type = Channel.GUILD_FORUM)
        val post = channel(12, guildId = 20, type = Channel.PUBLIC_THREAD, parentId = 10)
        val store = store()

        dispatcher.schedule {
            store.handleConnectionOpen(readPayload(false, ModelReadState(12, oldMessage, 0, 1)))
            store.updateUnreadInputs(
                StoreReadStates.Inputs(
                    channelPermissions = mapOf(10L to VIEW_CHANNEL, 12L to VIEW_CHANNEL),
                    channels = mapOf(10L to forum, 12L to post),
                    recentMessageIds = mapOf(12L to newMessage),
                ),
            )
        }
        scheduler.triggerActions()
        assertEquals(emptySet<Long>(), store.getUnreadChannelIdsSnapshot())

        dispatcher.schedule {
            store.updateActiveThreads(emptyMap(), activeForumThreads = mapOf(12L to post))
        }
        scheduler.triggerActions()
        assertEquals(setOf(12L), store.getUnreadChannelIdsSnapshot())
    }

    @Test
    fun textInVoiceIsUnreadOnlyWhileThatVoiceChannelIsSelectedAndOnlyForLaterMessages() {
        val oldMessage = snowflake(1_600_000_000_000L)
        val newMessage = snowflake(1_650_000_000_000L)
        val voice = channel(10, guildId = 20, type = Channel.GUILD_VOICE)
        val store = store()

        dispatcher.schedule {
            store.handleConnectionOpen(readPayload(false, ModelReadState(10, oldMessage, 0, 1)))
            store.updateUnreadInputs(
                StoreReadStates.Inputs(
                    channelPermissions = mapOf(10L to (VIEW_CHANNEL or CONNECT)),
                    channels = mapOf(10L to voice),
                    recentMessageIds = mapOf(10L to newMessage),
                ),
            )
        }
        scheduler.triggerActions()
        // Not in the channel: text in voice never badges.
        assertEquals(emptySet<Long>(), store.getUnreadChannelIdsSnapshot())

        // Joined after the message was sent: still nothing, the backlog is not "new".
        dispatcher.schedule { store.updateSelectedVoiceChannel(10, 1_660_000_000_000L) }
        scheduler.triggerActions()
        assertEquals(emptySet<Long>(), store.getUnreadChannelIdsSnapshot())

        // Joined before the message was sent.
        dispatcher.schedule { store.updateSelectedVoiceChannel(10, 1_640_000_000_000L) }
        scheduler.triggerActions()
        assertEquals(setOf(10L), store.getUnreadChannelIdsSnapshot())
    }

    // -- helpers --------------------------------------------------------------------------------

    private fun readPayload(partial: Boolean, vararg states: ModelReadState) = ModelPayload().also {
        it.readState = ModelPayload.VersionedReadStates().also { versioned ->
            versioned.version = 3
            versioned.isPartial = partial
            versioned.entries = states.toList()
        }
    }

    private fun channel(id: Long, guildId: Long, type: Int = Channel.GUILD_TEXT, parentId: Long = 0) = Channel(
        topic = null,
        type = type,
        guildId = guildId,
        name = "channel",
        lastMessageId = 0,
        id = id,
        ownerId = 0,
        recipientIds = null,
        recipients = null,
        position = 0,
        permissionOverwrites = null,
        userLimit = 0,
        bitrate = 0,
        icon = null,
        originChannelId = 0,
        applicationId = 0,
        nicks = null,
        nsfw = false,
        parentId = parentId,
        memberListId = null,
        rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null,
        rtcRegion = null,
        flags = null,
        guildHashes = null,
        availableTags = null,
        appliedTags = null,
        threadMetadata = null,
        messageCount = null,
        memberCount = null,
        memberIdsPreview = null,
        member = null,
    )

    private fun snowflake(timestamp: Long) = (timestamp - DISCORD_EPOCH) shl 22

    companion object {
        private const val DISCORD_EPOCH = 1_420_070_400_000L
        private const val NOW = 1_700_000_000_000L
        private const val VIEW_CHANNEL = 1024L
        private const val CONNECT = 1048576L

        private val directory = Files.createTempDirectory("read-states-unread")

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
