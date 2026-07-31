package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.voice.server.VoiceServer
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.rtcconnection.RtcConnection
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import rx.Observable
import rx.observers.TestSubscriber
import rx.plugins.RxJavaHooks
import rx.schedulers.Schedulers
import rx.schedulers.TestScheduler

class StoreRtcConnectionTest {

    @Before
    fun setUpSchedulers() {
        RxJavaHooks.setOnComputationScheduler { Schedulers.immediate() }
    }

    @After
    fun resetSchedulers() = RxJavaHooks.reset()

    @Test
    fun selectionAndServerUpdatesConnectInEitherArrivalOrder() {
        val selectionFirst = Harness()
        selectionFirst.dispatch {
            selectionFirst.store.handleConnectionOpen(readyPayload())
            selectionFirst.store.handleVoiceChannelSelected(CHANNEL_ID)
            selectionFirst.store.handleVoiceServerUpdate(voiceServer())
        }
        assertEquals(
            Connection(CHANNEL_ID, GUILD_ID, SESSION_ID),
            selectionFirst.controller.connections.single(),
        )

        val serverFirst = Harness()
        serverFirst.dispatch {
            serverFirst.store.handleConnectionOpen(readyPayload())
            serverFirst.store.handleVoiceServerUpdate(voiceServer())
            serverFirst.store.handleVoiceChannelSelected(CHANNEL_ID)
        }
        assertEquals(
            Connection(CHANNEL_ID, GUILD_ID, SESSION_ID),
            serverFirst.controller.connections.single(),
        )
    }

    @Test
    fun voiceStatePayloadCarriesSelectionFlagsAndPreferredRegion() {
        val harness = Harness(preferredRegion = "us-west")
        harness.dispatch {
            harness.store.handleConnectionOpen(readyPayload())
            harness.store.handleVoiceChannelSelected(CHANNEL_ID)
            harness.store.handleSelfMuted(true)
            harness.store.handleSelfDeafened(true)
            harness.store.handleSelfVideo(true)
        }

        assertEquals(
            VoicePayload(GUILD_ID, CHANNEL_ID, true, true, true, "us-west", true),
            harness.payloads.last(),
        )

        harness.dispatch { harness.store.handleVoiceChannelSelected(null) }
        val leave = harness.payloads.last()
        assertNull(leave.guildId)
        assertNull(leave.channelId)
        assertNull(leave.preferredRegion)
        assertFalse(leave.includePreferredRegion)
    }

    @Test
    fun voiceStateAndSpeakingUpdatesReachVoicePropsTracker() {
        val harness = Harness()
        harness.dispatch {
            harness.store.handleConnectionOpen(readyPayload())
            harness.store.handleVoiceChannelSelected(CHANNEL_ID)
        }
        harness.store.onSpeaking(LOCAL_USER_ID, true)
        harness.dispatch {}
        harness.dispatch { harness.store.handleVoiceStateUpdate(voiceState(600L)) }

        val tracker = StoreRtcConnection::class.java.getDeclaredField("voicePropsTracker").run {
            isAccessible = true
            get(harness.store)
        } as com.discord.rtcconnection.VoicePropsTracker
        val properties = linkedMapOf<String, Any>()
        tracker.getProps(properties)

        assertEquals(1, properties["max_voice_state_count"])
        assertEquals(1, properties["total_voice_state_count"])
        assertEquals(1, properties["max_listener_count"])
        assertEquals(1, properties["total_listener_count"])
        assertEquals(1, properties["max_speaker_count"])
        assertEquals(1, properties["total_speaker_count"])
    }

    @Test
    fun fullConnectionStatePreservesTransitionsWhileBooleanConsumersStillWork() {
        val harness = Harness()
        val changes = TestSubscriber<RtcConnection.StateChange>()
        val connected = TestSubscriber<Boolean>()
        harness.store.getConnectionState().subscribe(changes)
        harness.store.connectionState.subscribe(connected)

        val metadata = RtcConnection.Metadata("connection", "media", CHANNEL_ID, GUILD_ID, null)
        val connecting = RtcConnection.StateChange(RtcConnection.State.Authenticating, metadata)
        harness.store.onStateChange(connecting)
        harness.store.onStateChange(connecting)
        harness.controller.metadata = metadata
        harness.store.onConnectedChanged(true)

        changes.assertValues(
            RtcConnection.StateChange(RtcConnection.State.Disconnected(false), null),
            connecting,
            RtcConnection.StateChange(RtcConnection.State.RtcConnected, metadata),
        )
        connected.assertValues(false, true)
    }

    @Test
    fun focusedParticipantReachesRtcController() {
        val harness = Harness()

        harness.store.updateFocusedParticipant(600L)

        assertEquals(600L, harness.controller.recordedFocusedParticipant)
    }

    private class Harness(preferredRegion: String? = null) {
        private val scheduler = TestScheduler()
        private val dispatcher = Dispatcher(scheduler).apply {
            registerDispatchHandlers(DispatchHandler {})
        }
        val controller = FakeRtcController()
        val payloads = mutableListOf<VoicePayload>()
        private val region = StoreRtcRegion(
            dispatcher,
            Clock { 0L },
            { Observable.just(emptyList()) },
            { false },
            { _, _ -> },
        ).also { store ->
            StoreRtcRegion::class.java.getDeclaredField("lastTestResult").apply {
                isAccessible = true
                set(
                    store,
                    RtcLatencyTestResult(
                        if (preferredRegion == null) emptyList() else listOf(preferredRegion),
                    ),
                )
            }
        }
        val store = StoreRtcConnection(
            dispatcher = dispatcher,
            storeRtcRegion = region,
            findChannel = { id -> channel(id) },
            sendVoiceStateUpdate = { guildId, channelId, mute, deaf, video, region, include ->
                payloads += VoicePayload(guildId, channelId, mute, deaf, video, region, include)
            },
            rtcConnectionController = controller,
            getLocalUserId = { LOCAL_USER_ID },
            getVoiceStates = { emptyMap() },
        )

        fun dispatch(action: () -> Unit) {
            dispatcher.schedule(action)
            scheduler.triggerActions()
        }
    }

    private class FakeRtcController : StoreRtcConnection.RtcConnectionController {
        val connections = mutableListOf<Connection>()
        var recordedFocusedParticipant: Long? = null
        override var metadata: RtcConnection.Metadata? = null

        override fun connect(server: VoiceServer, channelId: Long, guildId: Long?, sessionId: String) {
            connections += Connection(channelId, guildId, sessionId)
        }

        override fun disconnect(reason: String) = Unit
        override fun setUserMuted(userId: Long, muted: Boolean) = Unit
        override fun setUserOutputVolume(userId: Long, volume: Float) = Unit
        override fun setUserOffScreen(userId: Long, offScreen: Boolean) = Unit
        override fun setFocusedParticipant(userId: Long?) {
            recordedFocusedParticipant = userId
        }
    }

    private data class Connection(val channelId: Long, val guildId: Long?, val sessionId: String)

    private data class VoicePayload(
        val guildId: Long?,
        val channelId: Long?,
        val selfMute: Boolean,
        val selfDeaf: Boolean,
        val selfVideo: Boolean,
        val preferredRegion: String?,
        val includePreferredRegion: Boolean,
    )

    private companion object {
        private const val GUILD_ID = 5L
        private const val CHANNEL_ID = 50L
        private const val SESSION_ID = "session"
        private const val LOCAL_USER_ID = 500L

        private val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()

        @JvmStatic
        @BeforeClass
        fun initializePersister() {
            Persister.init(Files.createTempDirectory("rtc-connection-test").toFile())
        }

        fun readyPayload(): ModelPayload = gson.fromJson(
            """{"session_id":"$SESSION_ID"}""",
            ModelPayload::class.java,
        )

        fun voiceServer() = VoiceServer(GUILD_ID, null, "voice.example", "token")

        fun voiceState(userId: Long) = com.discord.api.voice.state.VoiceState(
            userId = userId,
            suppress = false,
            sessionId = SESSION_ID,
            selfMute = false,
            selfDeaf = false,
            selfVideo = false,
            selfStream = false,
            mute = false,
            guildId = GUILD_ID,
            deaf = false,
            member = null,
            requestToSpeakTimestamp = null,
            channelId = CHANNEL_ID,
        )

        fun channel(id: Long) = Channel(
            topic = null,
            type = Channel.GUILD_VOICE,
            guildId = GUILD_ID,
            name = "voice",
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
    }
}
