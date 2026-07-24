package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadState
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreGuildSettingsReadStatesTest {
    @Test
    fun guildSettingsHonorCompleteAndPartialReadyAndPublishStableSnapshots() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreUserGuildSettings(dispatcher, { 1_000L }, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val observed = mutableListOf<Map<Long, ModelNotificationSettings>>()
        deck.connect(store, updateOnConnect = false) { observed += store.getGuildSettings() }

        dispatcher.schedule { store.handleConnectionOpen(settingsPayload(false, setting(1), setting(2))) }
        scheduler.triggerActions()
        val first = store.getGuildSettings()
        dispatcher.schedule { store.handleConnectionOpen(settingsPayload(true, setting(2, muted = true))) }
        scheduler.triggerActions()

        assertEquals(setOf(1L, 2L), first.keys)
        assertFalse(requireNotNull(first[2]).isMuted)
        assertTrue(requireNotNull(store.getGuildSettings()[2]).isMuted)
        assertEquals(2, observed.size)

        dispatcher.schedule { store.setHideMutedChannels(2, true) }
        scheduler.triggerActions()
        assertEquals(setOf(2L), store.getGuildsToHideMutedChannelsIn())
    }

    @Test
    fun readStatesMergePartialReadyAndDeriveUnreadThenAck() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val acked = mutableListOf<Long>()
        val store = StoreReadStates(dispatcher, { 1_700_000_000_000L }, deck, { id, _, _ -> acked += id })
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val channel = channel(10, guildId = 20)
        val oldMessage = snowflake(1_600_000_000_000L)
        val newMessage = snowflake(1_650_000_000_000L)

        dispatcher.schedule {
            store.handleConnectionOpen(readPayload(false, ModelReadState(10, oldMessage, 0, 1)))
            store.updateUnreadInputs(
                StoreReadStates.Inputs(
                    channelPermissions = mapOf(10L to 1024L),
                    channels = mapOf(10L to channel),
                    recentMessageIds = mapOf(10L to newMessage),
                ),
            )
        }
        scheduler.triggerActions()
        val first = store.getReadStates()

        assertEquals(setOf(10L), store.getUnreadChannelIdsSnapshot())
        assertEquals(setOf(20L), store.getUnreadGuildIdsSnapshot())

        dispatcher.schedule { store.handleConnectionOpen(readPayload(true, ModelReadState(11, newMessage, 1, 2))) }
        scheduler.triggerActions()
        assertEquals(setOf(10L), first.keys)
        assertEquals(setOf(10L, 11L), store.getReadStates().keys)

        store.markAsRead(10)
        scheduler.triggerActions()
        assertEquals(listOf(10L), acked)
        assertEquals(emptySet<Long>(), store.getUnreadChannelIdsSnapshot())
        assertEquals(newMessage, store.getAck(10)?.messageId)
    }

    @Test
    fun unreadProjectionAggregatesGuildMentionsAndSuppressesMutedIndicators() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreReadStates(dispatcher, { 1_700_000_000_000L }, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val oldMessage = snowflake(1_600_000_000_000L)
        val newMessage = snowflake(1_650_000_000_000L)
        val channels = listOf(
            channel(10, 20),
            channel(11, 20),
            channel(12, 0, Channel.PUBLIC_THREAD, parentId = 10),
            channel(13, 30),
            channel(14, 0, Channel.DM),
        )

        dispatcher.schedule {
            store.handleConnectionOpen(readPayload(
                false,
                ModelReadState(10, oldMessage, 2, 1),
                ModelReadState(11, oldMessage, 3, 1),
                ModelReadState(12, oldMessage, 4, 1),
                ModelReadState(13, oldMessage, 5, 1),
                ModelReadState(14, oldMessage, 0, 1),
            ))
            store.updateUnreadInputs(StoreReadStates.Inputs(
                channelPermissions = channels.associate { it.id to 1024L },
                channels = channels.associateBy(Channel::id),
                guildSettings = mapOf(
                    20L to setting(20, mutedChannelId = 11),
                    30L to setting(30, muted = true),
                ),
                recentMessageIds = channels.associate { it.id to newMessage },
                activeJoinedThreads = mapOf(12L to StoreReadStates.ActiveJoinedThread(channels[2], false)),
                channelGuildIds = mapOf(12L to 20L),
            ))
        }
        scheduler.triggerActions()

        assertEquals(setOf(10L, 12L, 14L), store.getUnreadChannelIdsSnapshot())
        assertEquals(setOf(20L), store.getUnreadGuildIdsSnapshot())
        assertEquals(mapOf(10L to 2, 11L to 3, 12L to 4, 13L to 5), store.getMentionCountsByChannelSnapshot())
        assertEquals(mapOf(20L to 9, 30L to 5), store.getMentionCountsByGuildSnapshot())
    }

    private fun settingsPayload(partial: Boolean, vararg settings: ModelNotificationSettings) = ModelPayload().also {
        it.userGuildSettings = ModelPayload.VersionedUserGuildSettings().also { versioned ->
            versioned.version = 6
            versioned.isPartial = partial
            versioned.entries = settings.toList()
        }
    }

    private fun readPayload(partial: Boolean, vararg states: ModelReadState) = ModelPayload().also {
        it.readState = ModelPayload.VersionedReadStates().also { versioned ->
            versioned.version = 3
            versioned.isPartial = partial
            versioned.entries = states.toList()
        }
    }

    private fun setting(guildId: Long, muted: Boolean = false, mutedChannelId: Long? = null) = ModelNotificationSettings(
        muted = muted,
        derivedMute = muted,
        guildId = guildId,
        channelOverrides = mutedChannelId?.let {
            listOf(ModelNotificationSettings.ChannelOverride(muted = true, derivedMute = true, channelId = it))
        }.orEmpty(),
    )

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

    private fun snowflake(timestamp: Long) = (timestamp - 1_420_070_400_000L) shl 22

    companion object {
        private val directory = Files.createTempDirectory("guild-settings-read-states")

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
