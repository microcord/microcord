package com.discord.stores

import android.content.SharedPreferences
import com.discord.api.activity.ActivityType
import com.discord.api.channel.Channel
import com.discord.api.embeddedactivities.EmbeddedActivity
import com.discord.api.embeddedactivities.EmbeddedActivityInboundUpdate
import com.discord.api.guildjoinrequest.ApplicationStatus
import com.discord.api.guildjoinrequest.GuildJoinRequest
import com.discord.api.interaction.InteractionStateUpdate
import com.discord.api.message.Message as ApiMessage
import com.discord.api.science.Science
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommandLocalSendData
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadState
import com.discord.models.domain.ModelReadySupplemental
import com.discord.utilities.analytics.AnalyticsSink
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.time.Clock
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private class RecordingSink : AnalyticsSink {
        val events = mutableListOf<Science.Event>()

        override fun deliver(token: String?, events: List<Science.Event>, onFailure: () -> Unit) {
            this.events += events
        }
    }

    @Test
    fun supplementalPrivateChannelsRestoreAndIncrementMentionBadges() {
        fun privateChannel(id: Long, type: Int) = gson.fromJson("""{"id":$id,"type":$type}""", Channel::class.java)
        val initialDm = privateChannel(20, Channel.DM)
        val lazyGroup = privateChannel(21, Channel.GROUP_DM)
        val lazyDm = privateChannel(22, Channel.DM)
        val removedGroup = privateChannel(23, Channel.GROUP_DM)
        val managedGroup = privateChannel(24, Channel.GROUP_DM).copy(applicationId = 99)
        val ready = gson.fromJson(READY_JSON, ModelPayload::class.java).apply {
            privateChannels = listOf(initialDm)
            readState = ModelPayload.VersionedReadStates().also {
                it.entries = (20L..24L).map { id -> ModelReadState(id, 100, (id - 19).toInt(), 1) }
            }
        }
        stream.dispatcher.schedule { StoreStream.getChannels().reset() }
        stream.handleConnectionOpen(ready)
        StoreStream.selectChannel(1, 10)
        awaitSelectedChannel(10)
        val mentions = StoreStream.getMentions()
        assertEquals(mapOf(20L to 1), mentions.getMentionCounts())

        stream.handleGatewayDispatch("READY_SUPPLEMENTAL", ModelReadySupplemental(
            lazyPrivateChannels = listOf(lazyGroup, lazyDm, managedGroup),
        ))
        assertEquals(setOf(20L, 21L, 22L), StoreStream.getChannels().getChannelsForGuild(0).keys)
        assertEquals(mapOf(20L to 1, 21L to 2, 22L to 3), mentions.getMentionCounts())

        val groupMessage = apiMessage(101, lazyGroup.id).copy(guildId = null)
        stream.handleGatewayDispatch("MESSAGE_CREATE", groupMessage)
        stream.handleGatewayDispatch("MESSAGE_CREATE", groupMessage)
        stream.handleGatewayDispatch("MESSAGE_CREATE", apiMessage(102, lazyDm.id).copy(guildId = null))
        assertEquals(mapOf(20L to 1, 21L to 3, 22L to 4), mentions.getMentionCounts())

        // A truly absent channel must not resurrect an old server badge when opened later.
        stream.handleGatewayDispatch("CHANNEL_CREATE", removedGroup)
        assertFalse(mentions.getMentionCounts().containsKey(removedGroup.id))

        StoreStream.selectChannel(0, lazyGroup.id)
        awaitSelectedChannel(lazyGroup.id)
        assertFalse(mentions.getMentionCounts().containsKey(lazyGroup.id))
        stream.handleGatewayDispatch("MESSAGE_ACK", ModelReadState(lazyGroup.id, 101, 0, 2))
        StoreStream.selectChannel(1, 10)
        awaitSelectedChannel(10)
        assertEquals(mapOf(20L to 1, 22L to 4), mentions.getMentionCounts())
    }

    @Test
    fun guildSelectionRequestsGatewayThreadStream() {
        val guildId = 9_999L
        val payloads = mutableListOf<GuildSubscriptionPayload>()
        val subscription = StoreStream.getGuildSubscriptions().observeSubscriptions().subscribe(payloads::add)
        try {
            StoreStream.selectGuild(guildId)
            assertTrue(payloads.any { it.guildId == guildId && it.subscriptions.threads == true })
        } finally {
            subscription.unsubscribe()
        }
    }

    @Test
    fun guildTimeoutUsesTheProductionPermissionCallback() {
        val callback = StoreGuilds::class.java.getDeclaredField("onMeCommunicationEnabled").run {
            isAccessible = true
            get(StoreStream.getGuilds()) as Function1<*, *>
        }

        assertFalse("the production callback must capture StoreStream, not use the no-op default", callback.javaClass.declaredFields.isEmpty())
    }

    @Test
    fun approvedJoinRequestQueuesTheReconstructedSuccessDialog() {
        stream.handleConnectionOpen(gson.fromJson(READY_JSON, ModelPayload::class.java))
        stream.dispatcher.schedule {
            StoreStream.getGuildJoinRequests().handleGuildJoinRequestCreateOrUpdate(
                1L,
                GuildJoinRequest(
                    userId = 7L,
                    user = null,
                    guildId = 1L,
                    createdAt = "2026-08-10T00:00:00Z",
                    applicationStatus = ApplicationStatus.APPROVED,
                    rejectionReason = null,
                    inviteCode = null,
                    lastSeen = null,
                    actionedAt = null,
                ),
            )
        }

        val queue = StoreNotices::class.java.getDeclaredField("noticeQueue").run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(StoreStream.getNotices()) as Collection<StoreNotices.Notice>
        }
        assertTrue(queue.any { it.name == "GUILD_JOIN_REQUEST_SUCCESS_1" })
    }

    @Test
    fun productionNavigationAndReviewCallbacksReachAnalytics() {
        val sink = RecordingSink()
        AnalyticsSink.install(sink)
        try {
            AnalyticsTracker.tracker.clear()
            stream.handleConnectionOpen(gson.fromJson(READY_JSON, ModelPayload::class.java))
            StoreStream.getAnalytics().handleFingerprint("wiring-test")

            StoreStream.selectGuild(0L)
            StoreStream.selectGuild(1L)
            StoreStream.selectChannel(1L, 10L)
            StoreStream.selectChannel(1L, 12L)
            awaitSelectedChannel(12L)
            StoreStream.getAnalytics().handleFingerprint("wiring-test")

            val events = sink.events.filterIsInstance<Science.Event.MapObject>()
            assertTrue(events.any { it.type == "guild_viewed" })
            assertTrue(events.toString(), events.any { it.type == "channel_opened" && it.properties["channel_id"] == 12L })
            assertTrue(events.toString(), events.any { it.type == "text_in_voice_opened" })
        } finally {
            AnalyticsSink.reset()
            AnalyticsTracker.tracker.clear()
            AnalyticsTracker.tracker.setTrackingData(null, fingerprinted = false)
        }
    }

    @Test
    fun guildSubscriptionsUseTheStoreStreamVoiceProvider() {
        val provider = StoreGuildSubscriptions::class.java.getDeclaredField("getConnectedVoiceGuildId").run {
            isAccessible = true
            get(StoreStream.getGuildSubscriptions()) as Function0<*>
        }

        assertFalse("the production provider must capture StoreStream, not use the zero default", provider.javaClass.declaredFields.isEmpty())
    }

    @Test
    fun typingAndChannelMembersUseProductionProviders() {
        stream.handleConnectionOpen(gson.fromJson(READY_JSON, ModelPayload::class.java))

        val typing = StoreStream.getUsersTyping()
        val api = StoreUsersTyping::class.java.getDeclaredField("api").run {
            isAccessible = true
            get(typing) as Function0<*>
        }
        val onCooldown = StoreUsersTyping::class.java.getDeclaredField("onCooldown").run {
            isAccessible = true
            get(typing) as Function3<*, *, *, *>
        }
        assertFalse("typing must resolve StoreStream's REST client", api.javaClass.declaredFields.isEmpty())
        assertFalse("typing cooldowns must reach StoreSlowMode", onCooldown.javaClass.declaredFields.isEmpty())

        val members = StoreStream.getChannelMembers()
        val memberDispatcher = StoreChannelMembers::class.java.getDeclaredField("dispatcher").run {
            isAccessible = true
            get(members)
        }
        val getChannel = provider<Function1<Long, Channel?>>(members, "getChannel")
        val getGuildRoles = provider<Function1<Long, Map<Long, *>>>(members, "getGuildRoles")
        val getGuildMemberCount = provider<Function1<Long, Int>>(members, "getGuildMemberCount")

        assertTrue(memberDispatcher === stream.dispatcher)
        assertEquals(10L, getChannel.invoke(10L)?.id)
        assertTrue(1L in getGuildRoles.invoke(1L))
        assertEquals(1, getGuildMemberCount.invoke(1L))
    }

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
         * `@everyone` carries `VIEW_CHANNEL or CONNECT` so read-state channels stay visible and the
         * voice-channel analytics callback can resolve. `SEND_MESSAGES` is deliberately role-only
         * for the timeout permission-recomputation test.
         */
        private const val READY_JSON = """
        {
          "user": {"id":7,"username":"me","discriminator":"0000"},
          "session_id": "session",
          "private_channels": [],
          "read_state": {"version":1,"partial":false,"entries":[]},
          "guilds": [{
            "id": 1, "name": "Guild", "owner_id": 9, "member_count": 1, "unavailable": false, "features": [],
            "joined_at": "2020-01-01T00:00:00",
            "roles": [{"id":1,"name":"@everyone","permissions":1049600,"position":0,"color":0,
                       "hoist":false,"managed":false,"mentionable":false}],
            "members": [{"guild_id":1,"user":{"id":7,"username":"me","discriminator":"0000"},
                         "roles":[],"pending":false,"joined_at":"2020-01-01T00:00:00"}],
            "channels": [
              {"id":10,"type":0,"guild_id":1,"name":"general"},
              {"id":12,"type":2,"guild_id":1,"name":"voice"},
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

        private inline fun <reified T> provider(target: Any, name: String): T =
            StoreChannelMembers::class.java.getDeclaredField(name).run {
                isAccessible = true
                get(target) as T
            }

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
