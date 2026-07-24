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

    private fun setting(guildId: Long, muted: Boolean = false) = ModelNotificationSettings(
        muted = muted,
        derivedMute = muted,
        guildId = guildId,
    )

    private fun channel(id: Long, guildId: Long) = Channel(
        topic = null,
        type = Channel.GUILD_TEXT,
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
        parentId = 0,
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
