package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.models.domain.ModelApplicationStream
import com.discord.models.domain.ModelPayload
import com.discord.rtcconnection.RtcConnection
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import java.nio.file.Files
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreVoiceChannelSelectedTest {

    @Test
    fun streamTargetingAndConnectedAfkCameraCorrectionMatchReference() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        val channels = StoreChannels(dispatcher, deck)
        val voiceStates = StoreVoiceStates({ _, _, _ -> }, deck)
        val permissions = StorePermissions(users, channels, guilds, deck)
        val sounds = mutableListOf<StoreVoiceChannelSelected.Sound>()
        val selectedVideoDevices = mutableListOf<String?>()
        var hasSelectedVideoInputDevice = true
        val store = StoreVoiceChannelSelected(
            dispatcher = dispatcher,
            clock = Clock { 0L },
            observationDeck = deck,
            channels = channels,
            voiceStates = voiceStates,
            permissions = permissions,
            getMeId = { 1L },
            playSound = { sounds += it },
            setAudioInputEnabled = {},
            getAfkChannelId = { guildId -> if (guildId == 5L) 20L else null },
            hasSelectedVideoInputDevice = { hasSelectedVideoInputDevice },
            selectVideoInputDevice = {
                selectedVideoDevices += it
                hasSelectedVideoInputDevice = it != null
            },
        )
        dispatcher.registerDispatchHandlers(
            StoreV2DispatchHandler(
                listOf(users, guilds, channels, voiceStates, permissions, store),
                deck,
            ),
        )
        val payload = ModelPayload().apply {
            sessionId = "session"
            privateChannels = listOf(channel(10L), channel(20L, guildId = 5L))
        }

        dispatcher.schedule {
            channels.handleConnectionOpen(payload)
            store.handleConnectionOpen(payload)
            store.handleStreamTargeted(ModelApplicationStream.CallStream(10L, 2L).encodedStreamKey)
        }
        scheduler.triggerActions()
        sounds.clear()

        dispatcher.schedule {
            store.handleRtcConnectionStateChanged(RtcConnection.State.RtcConnected)
        }
        scheduler.triggerActions()
        assertEquals(emptyList<String?>(), selectedVideoDevices)

        dispatcher.schedule {
            store.handleStreamTargeted(ModelApplicationStream.CallStream(20L, 3L).encodedStreamKey)
            store.handleRtcConnectionStateChanged(RtcConnection.State.RtcConnecting)
        }
        scheduler.triggerActions()

        assertEquals(20L, store.getSelectedVoiceChannelId())
        assertEquals(20L, store.getLastSelectedVoiceChannelId())
        assertEquals(listOf(StoreVoiceChannelSelected.Sound.JOINED), sounds)
        assertEquals(emptyList<String?>(), selectedVideoDevices)

        dispatcher.schedule {
            store.handleRtcConnectionStateChanged(RtcConnection.State.RtcConnected)
            store.handleRtcConnectionStateChanged(RtcConnection.State.RtcConnected)
        }
        scheduler.triggerActions()
        assertEquals(listOf(null), selectedVideoDevices)
    }

    private fun channel(id: Long, guildId: Long = 0L) = Channel(
        topic = null,
        type = Channel.DM,
        guildId = guildId,
        name = "call",
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

    companion object {
        private val directory = Files.createTempDirectory("voice-channel-selected")

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
