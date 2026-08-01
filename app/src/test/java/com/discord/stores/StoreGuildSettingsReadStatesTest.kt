package com.discord.stores

import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun privateChannelOverrideIsOptimisticAndOnlyCurrentFailureRollsBack() {
        val previous = ModelNotificationSettings(
            isSuppressEveryone = true,
            isMobilePush = false,
            guildId = 0,
            channelOverrides = listOf(
                ModelNotificationSettings.ChannelOverride(channelId = 8, muted = true),
            ),
        )

        val optimistic = previous.withChannelOverride(
            channelId = 9,
            muted = true,
            muteConfig = null,
            messageNotifications = ModelNotificationSettings.FREQUENCY_NOTHING,
            flags = null,
            clock = { 1_000L },
        )

        assertTrue(requireNotNull(optimistic.getChannelOverride(9)).isMuted)
        assertEquals(
            ModelNotificationSettings.FREQUENCY_NOTHING,
            optimistic.getChannelOverride(9)?.messageNotifications,
        )
        assertTrue(requireNotNull(optimistic.getChannelOverride(8)).isMuted)
        assertTrue(optimistic.isSuppressEveryone)
        assertFalse(optimistic.isMobilePush)
        assertSame(previous, privateChannelRollback(2, 2, previous))
        assertNull(privateChannelRollback(3, 2, previous))
    }

    private fun settingsPayload(partial: Boolean, vararg settings: ModelNotificationSettings) = ModelPayload().also {
        it.userGuildSettings = ModelPayload.VersionedUserGuildSettings().also { versioned ->
            versioned.version = 6
            versioned.isPartial = partial
            versioned.entries = settings.toList()
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
