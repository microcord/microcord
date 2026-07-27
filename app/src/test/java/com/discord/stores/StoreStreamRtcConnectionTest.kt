package com.discord.stores

import com.discord.models.domain.ModelApplicationStream
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.StreamCreateOrUpdate
import com.discord.models.domain.StreamServerUpdate
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreStreamRtcConnectionTest {

    @Test
    fun createServerConnectAndDeleteDriveOneStreamLifecycle() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val controller = FakeStreamController()
        val store = StoreStreamRtcConnection(dispatcher, users, controller, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(users, store), deck))
        val stream = ModelApplicationStream.GuildStream(GUILD_ID, CHANNEL_ID, OWNER_ID)

        dispatcher.schedule {
            users.handleConnectionOpen(readyPayload())
            store.handleConnectionOpen(readyPayload())
            store.handleStreamCreate(StreamCreateOrUpdate(stream.encodedStreamKey, RTC_SERVER_ID))
            store.handleStreamServerUpdate(
                StreamServerUpdate(stream.encodedStreamKey, "stream.example", "token"),
            )
            store.handleStreamRtcConnectionStateChange(true)
        }
        scheduler.triggerActions()

        assertEquals(
            StreamConnection(ME_ID, GUILD_ID, CHANNEL_ID, SESSION_ID, RTC_SERVER_ID, OWNER_ID),
            controller.connections.single(),
        )
        assertEquals("stream.example" to "token", controller.serverUpdates.single())
        assertEquals(OWNER_ID to 300f, controller.volumeUpdates.single())
        assertTrue(store.getState().isConnected)
        assertEquals(stream.encodedStreamKey, store.getState().streamKey)

        dispatcher.schedule { store.handleStreamDelete() }
        scheduler.triggerActions()

        assertEquals(1, controller.disconnects)
        assertFalse(store.getState().isConnected)
        assertNull(store.getState().streamKey)
    }

    private class FakeStreamController : StoreStreamRtcConnection.StreamConnectionController {
        val connections = mutableListOf<StreamConnection>()
        val serverUpdates = mutableListOf<Pair<String?, String?>>()
        val volumeUpdates = mutableListOf<Pair<Long, Float>>()
        var disconnects = 0
        override var connectedChannelId: Long? = null
        override var connectedSessionId: String? = null

        override fun connect(
            userId: Long,
            guildId: Long?,
            channelId: Long,
            sessionId: String,
            rtcServerId: String,
            streamOwnerId: Long,
            streamKey: String,
        ) {
            connections += StreamConnection(
                userId, guildId, channelId, sessionId, rtcServerId, streamOwnerId,
            )
            connectedChannelId = channelId
            connectedSessionId = sessionId
        }

        override fun updateServer(endpoint: String?, token: String?) {
            serverUpdates += endpoint to token
        }

        override fun disconnect() {
            disconnects++
            connectedChannelId = null
            connectedSessionId = null
        }

        override fun setUserOutputVolume(userId: Long, volume: Float) {
            volumeUpdates += userId to volume
        }

        override fun setFocusedParticipant(userId: Long?) = Unit
    }

    private data class StreamConnection(
        val userId: Long,
        val guildId: Long?,
        val channelId: Long,
        val sessionId: String,
        val rtcServerId: String,
        val ownerId: Long,
    )

    private companion object {
        private const val ME_ID = 7L
        private const val GUILD_ID = 5L
        private const val CHANNEL_ID = 50L
        private const val OWNER_ID = 9L
        private const val SESSION_ID = "session"
        private const val RTC_SERVER_ID = "rtc-server"

        private val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()

        @JvmStatic
        @BeforeClass
        fun initializePersister() {
            Persister.init(Files.createTempDirectory("stream-rtc-test").toFile())
        }

        fun readyPayload(): ModelPayload = gson.fromJson(
            """{"user":{"id":$ME_ID,"username":"me","discriminator":"0000"},"session_id":"$SESSION_ID"}""",
            ModelPayload::class.java,
        )
    }
}
