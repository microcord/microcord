package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.voice.state.VoiceState
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreAudioManagerV2Test {

    @Test
    fun videoUseDetectorOnlyCountsTheCurrentUsersVideoInTheSelectedChannel() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val channels = StoreChannels(dispatcher, deck)
        val voiceStates = StoreVoiceStates({ _, _, _ -> }, deck)
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        dispatcher.registerDispatchHandlers(
            StoreV2DispatchHandler(listOf(channels, voiceStates, users), deck),
        )
        val detector = StoreAudioManagerV2.VideoUseDetector(channels, voiceStates, users)
        var detected = false

        dispatcher.schedule {
            users.handleConnectionOpen(readyPayload())
            voiceStates.handleConnectionOpen(readyPayload())
            channels.handleChannelOrThreadCreateOrUpdate(channel())
        }
        scheduler.triggerActions()

        dispatcher.schedule {
            voiceStates.handleVoiceStateUpdate(voiceState(ME_ID, selfStream = true))
            detected = detector.isMyVideoOn(CHANNEL_ID)
        }
        scheduler.triggerActions()
        assertTrue(detected)

        dispatcher.schedule {
            voiceStates.handleVoiceStateUpdate(voiceState(ME_ID, selfVideo = true))
            detected = detector.isMyVideoOn(CHANNEL_ID)
        }
        scheduler.triggerActions()
        assertTrue(detected)

        dispatcher.schedule {
            voiceStates.handleVoiceStateUpdate(voiceState(ME_ID, selfStream = false))
            voiceStates.handleVoiceStateUpdate(voiceState(OTHER_ID, selfVideo = true))
            detected = detector.isMyVideoOn(CHANNEL_ID)
        }
        scheduler.triggerActions()
        assertFalse(detected)
    }

    private companion object {
        private const val ME_ID = 7L
        private const val OTHER_ID = 8L
        private const val GUILD_ID = 5L
        private const val CHANNEL_ID = 50L
        private const val SESSION_ID = "session"

        private val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()

        @JvmStatic
        @BeforeClass
        fun initializePersister() {
            Persister.init(Files.createTempDirectory("audio-manager-test").toFile())
        }

        fun readyPayload(): ModelPayload = gson.fromJson(
            """{"user":{"id":$ME_ID,"username":"me","discriminator":"0000"},"session_id":"$SESSION_ID"}""",
            ModelPayload::class.java,
        )

        fun voiceState(userId: Long, selfVideo: Boolean = false, selfStream: Boolean = false) =
            VoiceState(
                userId = userId,
                suppress = false,
                sessionId = SESSION_ID,
                selfMute = false,
                selfDeaf = false,
                selfVideo = selfVideo,
                selfStream = selfStream,
                mute = false,
                guildId = GUILD_ID,
                deaf = false,
                member = null,
                requestToSpeakTimestamp = null,
                channelId = CHANNEL_ID,
            )

        fun channel() = Channel(
            topic = null,
            type = Channel.GUILD_VOICE,
            guildId = GUILD_ID,
            name = "voice",
            lastMessageId = 0,
            id = CHANNEL_ID,
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
    }
}
