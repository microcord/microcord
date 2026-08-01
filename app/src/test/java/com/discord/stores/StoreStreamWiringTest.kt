package com.discord.stores

import android.content.SharedPreferences
import com.discord.api.activity.ActivityType
import com.discord.api.channel.Channel
import com.discord.api.commands.GuildApplicationCommands
import com.discord.api.embeddedactivities.EmbeddedActivity
import com.discord.api.embeddedactivities.EmbeddedActivityInboundUpdate
import com.discord.api.interaction.InteractionStateUpdate
import com.discord.api.message.Message as ApiMessage
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommandLocalSendData
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.utilities.time.Clock
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Proves wirings the parity audit found missing in `StoreStream` itself, end to end
 * through the real store graph rather than against an isolated store.
 *
 * These defects were invisible from store-level tests: every store involved already behaved
 * correctly in isolation, and only `StoreStream` failed to connect them.
 */
class StoreStreamWiringTest {

    /** Thread and forum inputs reach the reactive read-state projection through their stores. */
    @Test
    fun threadUnreadReachesReadStates() {
        stream.handleConnectionOpen(gson.fromJson(READY_JSON, ModelPayload::class.java))

        // A thread the user has joined, arriving as its own THREAD_CREATE.
        stream.handleGatewayDispatch("THREAD_CREATE", gson.fromJson(JOINED_THREAD_JSON, Channel::class.java))
        // A forum post, which is unread without being joined.
        stream.handleGatewayDispatch("THREAD_CREATE", gson.fromJson(FORUM_POST_JSON, Channel::class.java))

        assertTrue(
            "THREAD_CREATE must reach StoreThreadsJoined, not the channel handler",
            StoreStream.getThreadsJoined().hasJoined(THREAD_ID),
        )
        assertTrue(
            "THREAD_CREATE must reach StoreThreadsActive's forum index",
            FORUM_POST_ID in StoreStream.getThreadsActive().getAllForumThreadsById(),
        )

        stream.handleGatewayDispatch("MESSAGE_CREATE", apiMessage(RECENT_MESSAGE_ID, THREAD_ID))
        stream.handleGatewayDispatch("MESSAGE_CREATE", apiMessage(RECENT_MESSAGE_ID + 1, FORUM_POST_ID))

        val unread = StoreStream.getReadStates().getUnreadChannelIds()
            .filter { THREAD_ID in it && FORUM_POST_ID in it }
            .take(1)
            .toBlocking()
            .single()
        assertTrue("joined thread must be unread, got $unread", THREAD_ID in unread)
        assertTrue("active forum post must be unread, got $unread", FORUM_POST_ID in unread)
    }

    /**
     * `StoreStream` never constructed a `StoreChat`, so `StoreMessagesLoader.init(chat, …)` was
     * never called and `handleChatInteraction` was unreachable — interaction-driven pagination
     * could not work at all.
     */
    @Test
    fun chatInteractionReachesMessagesLoader() {
        val seen = mutableListOf<StoreMessagesLoader.ChannelLoadedState>()
        val subscription = StoreStream.getMessagesLoader()
            .getMessagesLoadedState(THREAD_ID)
            .subscribe { seen += it }

        StoreStream.getChat().setInteractionState(
            StoreChat.InteractionState(
                channelId = THREAD_ID,
                lastMessageId = RECENT_MESSAGE_ID,
                isTouchedSinceLastJump = true,
                layoutManager = null,
            ),
        )
        subscription.unsubscribe()

        assertTrue(
            "StoreChat interaction state must reach StoreMessagesLoader, saw $seen",
            seen.any(StoreMessagesLoader.ChannelLoadedState::isTouchedSinceLastJump),
        )
    }

    @Test
    fun applicationCommandResultIsRoutedByNonce() {
        val commands = StoreStream.getApplicationCommands()
        commands.requestApplicationCommandsQuery(1L, "wir")
        val nonce = StoreApplicationCommands::class.java.getDeclaredField("queryNonce").run {
            isAccessible = true
            get(commands) as String
        }

        stream.handleGatewayDispatch(
            "GUILD_APPLICATION_COMMANDS_UPDATE",
            GuildApplicationCommands(
                guildId = 1L,
                updatedAt = 0L,
                applications = emptyList(),
                nonce = nonce,
                applicationCommands = listOf(
                    com.discord.api.commands.ApplicationCommand(
                        id = 44L,
                        applicationId = 9L,
                        name = "wired",
                        description = null,
                        options = null,
                        version = "1",
                        guildId = "1",
                        defaultPermissions = null,
                        permissions = null,
                    ),
                ),
            ),
        )

        assertEquals(listOf("wired"), commands.getQueryCommands().map { it.name })
    }

    @Test
    fun interactionEventsAndConnectionLifecycleReachStore() {
        val interactions = StoreStream.getInteractions()
        val nonce = "9001"
        stream.dispatcher.schedule {
            interactions.handlePreLogout()
            interactions.applicationCommandLocalSendDataSet[nonce] = ApplicationCommandLocalSendData(
                nonce = nonce.toLong(),
                channelId = 10L,
                guildId = 1L,
                commandGuildId = 1L,
                application = Application(id = 9L, name = "bot", commandCount = 1),
                sessionId = "session",
                applicationCommandName = "wired",
                applicationCommandId = "44",
                applicationCommandsValues = emptyList(),
                version = "1",
            )
            interactions.sentInteractions.put(
                nonce,
                StoreApplicationInteractions.SentInteraction(10L, null, 1L),
            )
        }

        stream.handleGatewayDispatch("INTERACTION_CREATE", InteractionStateUpdate(77L, nonce))
        assertEquals(77L, interactions.applicationCommandLocalSendDataSet[nonce]?.interactionId)

        stream.handleGatewayDispatch("INTERACTION_SUCCESS", InteractionStateUpdate(77L, nonce))
        assertEquals(
            StoreApplicationInteractions.SentInteractionState.SUCCESS,
            interactions.sentInteractions[nonce]?.state,
        )

        StoreStream.getGatewayConnection().handleConnectionReady(true)
        assertEquals(
            StoreApplicationInteractions.SentInteractionState.FAILURE,
            interactions.sentInteractions[nonce]?.state,
        )
    }

    @Test
    fun remainingGatewayStoresArePublishedAndRouted() {
        stream.handleGatewayDispatch("USER_SUBSCRIPTIONS_UPDATE", Unit)
        stream.handleGatewayDispatch("USER_PAYMENT_SOURCES_UPDATE", Unit)
        stream.handleGatewayDispatch(
            "EMBEDDED_ACTIVITY_UPDATE",
            EmbeddedActivityInboundUpdate(
                channelId = 10L,
                embeddedActivity = EmbeddedActivity(
                    applicationId = 99L,
                    assets = null,
                    createdAt = null,
                    details = null,
                    name = "Activity",
                    secrets = null,
                    state = null,
                    timestamps = null,
                    type = ActivityType.PLAYING,
                ),
                guildId = 1L,
                users = listOf(7L),
            ),
        )

        assertTrue(StoreStream.getSubscriptions().getSubscriptions() is StoreSubscriptions.SubscriptionsState.Failure)
        assertTrue(StoreStream.getPaymentSources().getPaymentSourcesState() is StorePaymentSources.PaymentSourcesState.Failure)
        assertEquals(listOf(7L), StoreStream.getEmbeddedActivities().getEmbeddedActivities()[10L]?.get(99L)?.userIds)
    }

    @Test
    fun completedStoresArePublishedAndRegistered() {
        val storesV2 = StoreStream::class.java.getDeclaredField("storesV2").run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(stream) as List<StoreV2>
        }
        val publishedV2 = listOf(
            StoreStream.getApplicationStreamPreviews(),
            StoreStream.getEntitlements(),
            StoreStream.getGooglePlayPurchases(),
            StoreStream.getGooglePlaySkuDetails(),
            StoreStream.getGuildsNsfw(),
            StoreStream.getMaskedLinks(),
            StoreStream.getMediaFavorites(),
            StoreStream.getNotificationUpsells(),
        )

        assertTrue(storesV2.containsAll(publishedV2))
        assertTrue(StoreStream.getAnalytics() is StoreAnalytics)
        assertTrue(StoreStream.getInviteSettings() is StoreInviteSettings)
    }

    @Test
    fun switchingGuildSurfacesRestoresTheirLastChannel() {
        stream.handleConnectionOpen(gson.fromJson(READY_JSON, ModelPayload::class.java))
        val dm = gson.fromJson(
            """{"id":20,"type":1,"guild_id":0,"recipients":[{"id":8,"username":"dm"}]}""",
            Channel::class.java,
        )
        StoreStream.getChannels().onGroupCreated(dm)
        repeat(100) {
            if (StoreStream.getChannels().getChannel(dm.id) != null) return@repeat
            Thread.sleep(5)
        }
        assertTrue("DM channel was not stored", StoreStream.getChannels().getChannel(dm.id) != null)
        StoreStream.selectChannel(0L, dm.id)
        awaitSelectedChannel(dm.id)

        StoreStream.selectGuild(1L)
        awaitSelectedChannel(10L)
        StoreStream.selectGuild(0L)
        awaitSelectedChannel(dm.id)

        assertEquals("DM selection was not restored", dm.id, StoreStream.getChannelsSelected().getId())
    }

    private fun awaitSelectedChannel(channelId: Long) {
        repeat(100) {
            if (StoreStream.getChannelsSelected().getId() == channelId) return
            Thread.sleep(5)
        }
    }

    companion object {
        private const val THREAD_ID = 11L
        private const val FORUM_POST_ID = 31L

        /** Snowflake for 2022-04-15, comfortably after the guild's `joined_at`. */
        private const val RECENT_MESSAGE_ID = (1_650_000_000_000L - 1_420_070_400_000L) shl 22

        /**
         * `@everyone` carries `VIEW_CHANNEL or SEND_MESSAGES` (3072) because `StoreReadStates`
         * drops any channel the user cannot see, and the read state starts empty so every channel
         * falls back to the guild's `joined_at` as its ack.
         */
        private const val READY_JSON = """
        {
          "user": {"id":7,"username":"me","discriminator":"0000"},
          "session_id": "session",
          "private_channels": [],
          "read_state": {"version":1,"partial":false,"entries":[]},
          "guilds": [{
            "id": 1, "name": "Guild", "owner_id": 9, "unavailable": false, "features": [],
            "joined_at": "2020-01-01T00:00:00",
            "roles": [{"id":1,"name":"@everyone","permissions":3072,"position":0,"color":0,
                       "hoist":false,"managed":false,"mentionable":false}],
            "members": [{"guild_id":1,"user":{"id":7,"username":"me","discriminator":"0000"},
                         "roles":[],"pending":false,"joined_at":"2020-01-01T00:00:00"}],
            "channels": [
              {"id":10,"type":0,"guild_id":1,"name":"general"},
              {"id":30,"type":15,"guild_id":1,"name":"forum"}
            ]
          }]
        }
        """

        private const val JOINED_THREAD_JSON = """
        {"id":11,"type":11,"guild_id":1,"name":"thread","parent_id":10,
         "thread_metadata":{"archived":false,"auto_archive_duration":1440,"locked":false},
         "member":{"id":11,"user_id":7,"flags":1,"join_timestamp":"2020-01-02T00:00:00","muted":false}}
        """

        private const val FORUM_POST_JSON = """
        {"id":31,"type":11,"guild_id":1,"name":"post","parent_id":30,
         "thread_metadata":{"archived":false,"auto_archive_duration":1440,"locked":false}}
        """

        /**
         * Only `STATIC` is excluded, matching the gateway parser: `Channel.member` is `@Transient`
         * yet the gateway really sends it, and it is what marks a thread as joined.
         */
        private val gson = GsonBuilder()
            .excludeFieldsWithModifiers(Modifier.STATIC)
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()

        private fun apiMessage(id: Long, channelId: Long): ApiMessage = gson.fromJson(
            """{"id":$id,"channel_id":$channelId,"guild_id":1,"type":0,
                "author":{"id":8,"username":"other","discriminator":"0001"},"content":"hi"}""",
            ApiMessage::class.java,
        )

        private val directory = Files.createTempDirectory("store-stream-wiring")

        /** Same single-threaded, run-to-completion scheduler `StoreStreamTest` uses. */
        private val scheduler = storeTestScheduler("wiring-store-thread")

        private val prefs = memoryPreferences()
        private val durablePrefs = memoryPreferences()
        private lateinit var stream: StoreStream

        @JvmStatic
        @BeforeClass
        fun initialize() {
            stream = StoreStream.initializeForTesting(
                directory.toFile(), prefs, durablePrefs, scheduler, Clock { 0 },
            )
        }

        @JvmStatic
        @AfterClass
        fun cleanUp() {
            // The executor's thread is a daemon and the singleton outlives this class, so it is
            // deliberately left running: shutting it down would reject any late dispatch.
            directory.toFile().deleteRecursively()
        }

        private fun memoryPreferences(): SharedPreferences {
            val values = linkedMapOf<String, Any?>()
            lateinit var editor: Any
            editor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "putBoolean", "putInt", "putLong", "putFloat", "putString", "putStringSet" ->
                        editor.also { values[args[0] as String] = args[1] }
                    "remove" -> editor.also { values.remove(args[0] as String) }
                    "clear" -> editor.also { values.clear() }
                    "commit" -> true
                    "apply" -> null
                    else -> editor
                }
            }
            return Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getAll" -> values.toMap()
                    "getBoolean", "getInt", "getLong", "getFloat", "getString", "getStringSet" ->
                        values[args[0]] ?: args[1]
                    "contains" -> values.containsKey(args[0])
                    "edit" -> editor
                    else -> null
                }
            } as SharedPreferences
        }
    }
}
