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
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreStreamRtcConnectionTest {

    @Test
    fun twoWatchedStreamsKeepSeparateConnections() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val controller = FakeStreamController()
        val store = StoreStreamRtcConnection(dispatcher, users, controller, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(users, store), deck))
        val first = ModelApplicationStream.GuildStream(GUILD_ID, CHANNEL_ID, OWNER_ID).encodedStreamKey
        val second = ModelApplicationStream.GuildStream(GUILD_ID, CHANNEL_ID, OTHER_OWNER_ID).encodedStreamKey

        dispatcher.schedule {
            users.handleConnectionOpen(readyPayload())
            store.handleConnectionOpen(readyPayload())
            store.handleStreamCreate(StreamCreateOrUpdate(first, RTC_SERVER_ID))
            store.handleStreamCreate(StreamCreateOrUpdate(second, RTC_SERVER_ID))
            // A resumed gateway re-sends STREAM_CREATE; the live connection must survive it.
            store.handleStreamCreate(StreamCreateOrUpdate(first, RTC_SERVER_ID))
            store.handleStreamServerUpdate(StreamServerUpdate(first, "stream.example", "token"))
            store.handleStreamRtcConnectionStateChange(first, true)
        }
        scheduler.triggerActions()

        assertEquals(
            listOf(
                StreamConnection(ME_ID, GUILD_ID, CHANNEL_ID, SESSION_ID, RTC_SERVER_ID, OWNER_ID),
                StreamConnection(ME_ID, GUILD_ID, CHANNEL_ID, SESSION_ID, RTC_SERVER_ID, OTHER_OWNER_ID),
            ),
            controller.connections,
        )
        assertEquals(Triple(first, "stream.example", "token"), controller.serverUpdates.single())
        assertEquals(OWNER_ID to 300f, controller.volumeUpdates.single())
        assertTrue(store.getStates().getValue(first).isConnected)
        assertFalse(store.getStates().getValue(second).isConnected)

        dispatcher.schedule {
            store.handleStreamDelete(first)
            // A late callback from the deleted connection does not bring it back.
            store.handleStreamRtcConnectionStateChange(first, false)
        }
        scheduler.triggerActions()

        assertEquals(listOf<String?>(first), controller.disconnects)
        assertEquals(setOf(second), store.getStates().keys)

        dispatcher.schedule { store.handleStreamDelete() }
        scheduler.triggerActions()

        assertEquals(listOf(first, null), controller.disconnects)
        assertTrue(store.getStates().isEmpty())
    }

    private class FakeStreamController : StoreStreamRtcConnection.StreamConnectionController {
        val connections = mutableListOf<StreamConnection>()
        val serverUpdates = mutableListOf<Triple<String, String?, String?>>()
        val volumeUpdates = mutableListOf<Pair<Long, Float>>()
        val disconnects = mutableListOf<String?>()
        private val sessions = HashMap<String, String>()

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
            sessions[streamKey] = sessionId
        }

        override fun updateServer(streamKey: String, endpoint: String?, token: String?) {
            serverUpdates += Triple(streamKey, endpoint, token)
        }

        override fun disconnect(streamKey: String?) {
            disconnects += streamKey
            if (streamKey == null) sessions.clear() else sessions.remove(streamKey)
        }

        override fun connectedSessionId(streamKey: String): String? = sessions[streamKey]

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
        private const val OTHER_OWNER_ID = 11L
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
