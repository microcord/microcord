package com.discord.gateway

import android.content.SharedPreferences
import com.discord.api.channel.ChannelRecipient
import com.discord.api.commands.ApplicationCommandAutocompleteResult
import com.discord.api.embeddedactivities.EmbeddedActivityInboundUpdate
import com.discord.api.emoji.GuildEmojisUpdate
import com.discord.api.forum.ForumUnreads
import com.discord.api.guildjoinrequest.GuildJoinRequestCreateOrUpdate
import com.discord.api.guildjoinrequest.GuildJoinRequestDelete
import com.discord.api.guildscheduledevent.GuildScheduledEventUserUpdate
import com.discord.api.interaction.InteractionModalCreate
import com.discord.api.interaction.InteractionStateUpdate
import com.discord.api.requiredaction.UserRequiredActionUpdate
import com.discord.api.sticker.GuildStickersUpdate
import com.discord.api.thread.ThreadMemberListUpdate
import com.discord.api.thread.ThreadMemberUpdate
import com.discord.api.thread.ThreadMembersUpdate
import com.discord.api.voice.server.VoiceServer
import com.discord.api.voice.state.VoiceState
import com.discord.gateway.io.IncomingParser
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelBan
import com.discord.models.domain.ModelCall
import com.discord.models.domain.ModelGuildIntegration
import com.discord.models.domain.StreamCreateOrUpdate
import com.discord.models.domain.StreamDelete
import com.discord.models.domain.StreamServerUpdate
import com.discord.stores.StoreGatewayConnection
import com.discord.stores.StoreStream
import com.discord.stores.storeTestScheduler
import com.discord.utilities.time.Clock
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rx.Observable

/**
 * Covers the dispatch families that were previously unparsed and unrouted: every fixture is parsed
 * into its typed payload and then pushed through [StoreGatewayConnection.handleDispatch] to prove it
 * lands on the subject its consumer store will subscribe to.
 *
 * All fixtures use synthetic ids and placeholder names; none is tied to a real account or guild.
 */
class GatewayDispatchRoutingTest {

    // region Guilds

    @Test
    fun routesGuildBans() {
        val added = dispatch<ModelBan>(
            "GUILD_BAN_ADD",
            """{"guild_id":10,"user":{"id":20,"username":"fixture-user","discriminator":"0001"},"reason":"fixture reason"}""",
            gateway.guildBanAdd,
        )
        assertEquals(10L, added.guildId)
        assertEquals(20L, added.user.id)
        assertEquals("fixture reason", added.reason)

        val removed = dispatch<ModelBan>(
            "GUILD_BAN_REMOVE",
            """{"guild_id":10,"user":{"id":20,"username":"fixture-user","discriminator":"0001"}}""",
            gateway.guildBanRemove,
        )
        assertEquals(10L, removed.guildId)
        assertNull(removed.reason)
    }

    @Test
    fun routesGuildEmojiAndStickerUpdates() {
        val emojis = dispatch<GuildEmojisUpdate>(
            "GUILD_EMOJIS_UPDATE",
            """{"guild_id":10,"emojis":[{"id":30,"name":"fixture_emoji","roles":[],"require_colons":true,"managed":false,"animated":false,"available":true}],"guild_hashes":{"version":1,"metadata":{"hash":"m","omitted":false},"channels":{"hash":"c","omitted":false},"roles":{"hash":"r","omitted":false}}}""",
            gateway.guildEmojisUpdate,
        )
        assertEquals(10L, emojis.guildId)
        assertEquals("fixture_emoji", emojis.emojis.single().name)
        assertEquals(1, emojis.guildHashes.version)

        val stickers = dispatch<GuildStickersUpdate>(
            "GUILD_STICKERS_UPDATE",
            """{"guild_id":10,"stickers":[],"guild_hashes":{"version":1,"metadata":{"hash":"m","omitted":false},"channels":{"hash":"c","omitted":false},"roles":{"hash":"r","omitted":false}}}""",
            gateway.guildStickersUpdate,
        )
        assertEquals(10L, stickers.guildId)
        assertTrue(stickers.stickers.isEmpty())
    }

    @Test
    fun routesGuildIntegrationsUpdate() {
        val update = dispatch<ModelGuildIntegration.Update>(
            "GUILD_INTEGRATIONS_UPDATE",
            """{"guild_id":10}""",
            gateway.guildIntegrationsUpdate,
        )
        assertEquals(10L, update.guildId)
    }

    @Test
    fun routesApplicationCommandAutocomplete() {
        val autocomplete = dispatch<ApplicationCommandAutocompleteResult>(
            "APPLICATION_COMMAND_AUTOCOMPLETE_RESPONSE",
            """{"nonce":"fixture-nonce","choices":[{"name":"first","value":"1"}]}""",
            gateway.applicationCommandAutocompleteResult,
        )
        assertEquals("first", autocomplete.choices.single().name)
    }

    @Test
    fun routesGuildJoinRequests() {
        val created = dispatch<GuildJoinRequestCreateOrUpdate>(
            "GUILD_JOIN_REQUEST_CREATE",
            """{"guild_id":10,"request":{"user_id":20,"guild_id":10,"created_at":"2021-08-01T12:00:00+00:00","application_status":"PENDING","rejection_reason":null,"invite_code":null,"last_seen":null,"actioned_at":null,"user":null}}""",
            gateway.guildJoinRequestCreateOrUpdate,
        )
        assertEquals(20L, created.request.userId)

        // UPDATE deliberately shares the CREATE subject: both carry a whole request row.
        val updated = dispatch<GuildJoinRequestCreateOrUpdate>(
            "GUILD_JOIN_REQUEST_UPDATE",
            """{"guild_id":10,"request":{"user_id":20,"guild_id":10,"created_at":"2021-08-01T12:00:00+00:00","application_status":"APPROVED","rejection_reason":null,"invite_code":null,"last_seen":null,"actioned_at":null,"user":null}}""",
            gateway.guildJoinRequestCreateOrUpdate,
        )
        assertEquals(10L, updated.guildId)

        val deleted = dispatch<GuildJoinRequestDelete>(
            "GUILD_JOIN_REQUEST_DELETE",
            """{"guild_id":10,"user_id":20}""",
            gateway.guildJoinRequestDelete,
        )
        assertEquals(20L, deleted.userId)
    }

    @Test
    fun routesGuildScheduledEventUserUpdates() {
        val added = dispatch<GuildScheduledEventUserUpdate>(
            "GUILD_SCHEDULED_EVENT_USER_ADD",
            """{"guild_scheduled_event_id":40,"guild_id":10,"user_id":20}""",
            gateway.guildScheduledEventUserAdd,
        )
        assertEquals(40L, added.guildScheduledEventId)

        val removed = dispatch<GuildScheduledEventUserUpdate>(
            "GUILD_SCHEDULED_EVENT_USER_REMOVE",
            """{"guild_scheduled_event_id":40,"guild_id":10,"user_id":20}""",
            gateway.guildScheduledEventUserRemove,
        )
        assertEquals(20L, removed.userId)
    }

    // endregion

    // region Channels and threads

    @Test
    fun routesChannelRecipients() {
        val added = dispatch<ChannelRecipient>(
            "CHANNEL_RECIPIENT_ADD",
            """{"channel_id":50,"nick":"fixture-nick","user":{"id":20,"username":"fixture-user","discriminator":"0001"}}""",
            gateway.channelRecipientAdd,
        )
        assertEquals(50L, added.channelId)
        assertEquals("fixture-nick", added.nick)
        assertEquals(20L, added.user.id)

        val removed = dispatch<ChannelRecipient>(
            "CHANNEL_RECIPIENT_REMOVE",
            """{"channel_id":50,"nick":null,"user":{"id":20,"username":"fixture-user","discriminator":"0001"}}""",
            gateway.channelRecipientRemove,
        )
        assertNull(removed.nick)
    }

    @Test
    fun routesThreadMembershipUpdates() {
        val member = dispatch<ThreadMemberUpdate>(
            "THREAD_MEMBER_UPDATE",
            """{"id":60,"guild_id":10,"user_id":20,"flags":1,"join_timestamp":"2021-08-01T12:00:00.000000+00:00","muted":false,"mute_config":null}""",
            gateway.threadMemberUpdate,
        )
        assertEquals(60L, member.id)
        assertEquals(20L, member.userId)

        val members = dispatch<ThreadMembersUpdate>(
            "THREAD_MEMBERS_UPDATE",
            """{"id":60,"guild_id":10,"member_count":3,"removed_member_ids":["21"],"member_ids_preview":["20","22"],"added_members":[]}""",
            gateway.threadMembersUpdate,
        )
        assertEquals(3, members.memberCount)
        assertEquals(listOf(21L), members.removedMemberIds)
        assertEquals(listOf(20L, 22L), members.memberIdsPreview)

        val list = dispatch<ThreadMemberListUpdate>(
            "THREAD_MEMBER_LIST_UPDATE",
            """{"guild_id":10,"thread_id":60,"members":[{"user_id":20,"member":null,"presence":null}]}""",
            gateway.threadMemberListUpdate,
        )
        assertEquals(60L, list.threadId)
        assertEquals(20L, list.members?.single()?.userId)
    }

    @Test
    fun routesForumUnreads() {
        val unreads = dispatch<ForumUnreads>(
            "FORUM_UNREADS",
            """{"channel_id":50,"permission_denied":false,"threads":[{"thread_id":60,"missing":false,"count":4}]}""",
            gateway.forumUnreads,
        )
        assertEquals(50L, unreads.channelId)
        assertEquals(4, unreads.threads.single().count)
    }

    // endregion

    // region Voice, calls and streams

    /**
     * Voice states are coalesced, so nothing is emitted until another dispatch type forces a flush.
     */
    @Test
    fun batchesVoiceStateUpdatesUntilAnotherEventFlushesThem() {
        val batches = mutableListOf<List<VoiceState>>()
        val subscription = gateway.voiceStateUpdate.observe().subscribe(batches::add)
        try {
            handle("VOICE_STATE_UPDATE", """{"user_id":20,"guild_id":10,"channel_id":70,"session_id":"fixture-session","suppress":false,"self_mute":false,"self_deaf":false,"self_video":false,"self_stream":false,"mute":false,"deaf":false}""")
            handle("VOICE_STATE_UPDATE", """{"user_id":21,"guild_id":10,"channel_id":70,"session_id":"fixture-session-2","suppress":false,"self_mute":true,"self_deaf":false,"self_video":false,"self_stream":false,"mute":false,"deaf":false}""")
            assertTrue("nothing should be emitted before a flush", batches.none { it.isNotEmpty() })

            handle("VOICE_SERVER_UPDATE", """{"guild_id":10,"channel_id":null,"endpoint":"fixture.endpoint","token":"fixture-token"}""")

            val flushed = batches.last { it.isNotEmpty() }
            assertEquals(listOf(20L, 21L), flushed.map(VoiceState::userId))
            assertEquals(70L, flushed.first().channelId)
            assertTrue(flushed[1].selfMute)
        } finally {
            subscription.unsubscribe()
        }
    }

    @Test
    fun routesVoiceServerUpdate() {
        val server = dispatch<VoiceServer>(
            "VOICE_SERVER_UPDATE",
            """{"guild_id":10,"channel_id":null,"endpoint":"fixture.endpoint","token":"fixture-token"}""",
            gateway.voiceServerUpdate,
        )
        assertEquals(10L, server.guildId)
        assertNull(server.channelId)
        assertEquals("fixture.endpoint", server.endpoint)
        assertTrue("token must never be rendered", "fixture-token" !in server.toString())
    }

    @Test
    fun routesCalls() {
        val created = dispatch<ModelCall>(
            "CALL_CREATE",
            """{"channel_id":50,"message_id":80,"region":"fixture-region","ringing":["20"],"voice_states":[]}""",
            gateway.callCreateOrUpdate,
        )
        assertEquals(50L, created.channelId)
        assertEquals(listOf(20L), created.ringingUserIds)

        // CALL_UPDATE shares the create subject; only CALL_DELETE is separate.
        val updated = dispatch<ModelCall>(
            "CALL_UPDATE",
            """{"channel_id":50,"message_id":81,"region":"fixture-region","ringing":[]}""",
            gateway.callCreateOrUpdate,
        )
        assertEquals(81L, updated.messageId)

        val deleted = dispatch<ModelCall>(
            "CALL_DELETE",
            """{"channel_id":50,"unavailable":true}""",
            gateway.callDelete,
        )
        assertTrue(deleted.unavailable)
    }

    @Test
    fun routesStreams() {
        val created = dispatch<StreamCreateOrUpdate>(
            "STREAM_CREATE",
            """{"stream_key":"guild:10:70:20","rtc_server_id":"fixture-rtc","paused":false,"viewer_ids":["21"]}""",
            gateway.streamCreate,
        )
        assertEquals("guild:10:70:20", created.streamKey)
        assertEquals(listOf(21L), created.viewers)

        val updated = dispatch<StreamCreateOrUpdate>(
            "STREAM_UPDATE",
            """{"stream_key":"guild:10:70:20","paused":true}""",
            gateway.streamUpdate,
        )
        assertTrue(updated.paused)
        assertTrue(updated.viewers.isEmpty())

        val deleted = dispatch<StreamDelete>(
            "STREAM_DELETE",
            """{"stream_key":"guild:10:70:20","reason":"stream_full","unavailable":false}""",
            gateway.streamDelete,
        )
        assertEquals(StreamDelete.Reason.STREAM_FULL, deleted.deleteReason)

        val unknownReason = dispatch<StreamDelete>(
            "STREAM_DELETE",
            """{"stream_key":"guild:10:70:20","reason":"something_new"}""",
            gateway.streamDelete,
        )
        assertEquals(StreamDelete.Reason.UNKNOWN, unknownReason.deleteReason)

        val server = dispatch<StreamServerUpdate>(
            "STREAM_SERVER_UPDATE",
            """{"stream_key":"guild:10:70:20","endpoint":"fixture.endpoint","token":"fixture-token"}""",
            gateway.streamServerUpdate,
        )
        assertEquals("fixture.endpoint", server.endpoint)
        assertTrue("token must never be rendered", "fixture-token" !in server.toString())
    }

    // endregion

    // region Interactions, embedded activities and account nudges

    @Test
    fun routesInteractionStateUpdates() {
        val created = dispatch<InteractionStateUpdate>(
            "INTERACTION_CREATE",
            """{"id":90,"nonce":"fixture-nonce"}""",
            gateway.interactionCreate,
        )
        assertEquals(90L, created.id)

        val succeeded = dispatch<InteractionStateUpdate>(
            "INTERACTION_SUCCESS",
            """{"id":90,"nonce":"fixture-nonce"}""",
            gateway.interactionSuccess,
        )
        assertEquals("fixture-nonce", succeeded.nonce)

        val failed = dispatch<InteractionStateUpdate>(
            "INTERACTION_FAILURE",
            """{"id":null,"nonce":"fixture-nonce"}""",
            gateway.interactionFailure,
        )
        assertNull(failed.id)

        val modal = dispatch<InteractionModalCreate>(
            "INTERACTION_MODAL_CREATE",
            """{"id":91,"custom_id":"fixture-modal","title":"Fixture","nonce":"fixture-nonce","components":[],"application":{"id":92,"name":"Fixture App","icon":null,"command_count":0,"bot":null,"built_in":false}}""",
            gateway.interactionModalCreate,
        )
        assertEquals("fixture-modal", modal.customId)
        assertEquals(92L, modal.application.id)
    }

    @Test
    fun routesEmbeddedActivityUpdate() {
        val update = dispatch<EmbeddedActivityInboundUpdate>(
            "EMBEDDED_ACTIVITY_UPDATE",
            """{"channel_id":50,"guild_id":10,"users":["20","21"],"embedded_activity":{"application_id":92,"name":"Fixture Activity","type":0}}""",
            gateway.embeddedActivityInboundUpdate,
        )
        assertEquals(50L, update.channelId)
        assertEquals(listOf(20L, 21L), update.users)
        assertEquals(92L, update.embeddedActivity.applicationId)
    }

    @Test
    fun routesUserRequiredActionUpdate() {
        val update = dispatch<UserRequiredActionUpdate>(
            "USER_REQUIRED_ACTION_UPDATE",
            """{"required_action":"REQUIRE_VERIFIED_EMAIL"}""",
            gateway.userRequiredActionUpdate,
        )
        assertEquals("REQUIRE_VERIFIED_EMAIL", update.requiredAction)
    }

    /**
     * `USER_CONNECTIONS_UPDATE` carries a body the client discards, so the parser yields [Unit].
     * The other two nudges arrive with no `d` at all and the socket substitutes [Unit] for them, so
     * the parser deliberately has no branch for them.
     */
    @Test
    fun routesBodylessAccountNudges() {
        assertSame(Unit, parseBodyless("USER_CONNECTIONS_UPDATE"))
        // Parsed but intentionally unrouted in the reference: no store consumes it yet.
        assertSame(Unit, parseBodyless("USER_CONNECTIONS_INTEGRATION_JOINING"))
        assertSame(IncomingParser.DISPATCH_UNPARSED, parseBodyless("USER_SUBSCRIPTIONS_UPDATE"))
        assertSame(IncomingParser.DISPATCH_UNPARSED, parseBodyless("USER_PAYMENT_SOURCES_UPDATE"))

        assertEquals(Unit, receiveNext(gateway.userConnectionUpdate) { gateway.handleDispatch("USER_CONNECTIONS_UPDATE", Unit) })
        assertEquals(Unit, receiveNext(gateway.userSubscriptionsUpdate) { gateway.handleDispatch("USER_SUBSCRIPTIONS_UPDATE", Unit) })
        assertEquals(Unit, receiveNext(gateway.userPaymentSourcesUpdate) { gateway.handleDispatch("USER_PAYMENT_SOURCES_UPDATE", Unit) })
    }

    // endregion

    /** Parses a frame whose `d` is present but empty, which is how the nudges arrive. */
    private fun parseBodyless(type: String): Any? =
        parser.parse("""{"op":0,"s":1,"t":"$type","d":{}}""").data

    private fun parse(type: String, body: String): Any =
        requireNotNull(parser.parse("""{"op":0,"s":1,"t":"$type","d":$body}""").data)

    private fun handle(type: String, body: String) {
        val data = parser.parse("""{"op":0,"s":1,"t":"$type","d":$body}""").data
        gateway.handleDispatch(type, requireNotNull(data))
    }

    /** Parses [body], asserts it reached [subject], and returns the typed payload. */
    private inline fun <reified T : Any> dispatch(type: String, body: String, subject: Observable<T>): T {
        val parsed = parse(type, body)
        assertTrue("$type parsed to ${parsed.javaClass.name}", parsed is T)
        val received = receiveNext(subject) { gateway.handleDispatch(type, parsed) }
        assertSame("$type did not reach its subject", parsed, received)
        return parsed as T
    }

    private fun <T> receiveNext(subject: Observable<T>, action: () -> Unit): T? {
        var received: T? = null
        val subscription = subject.subscribe { received = it }
        try {
            action()
        } finally {
            subscription.unsubscribe()
        }
        return received
    }

    companion object {
        private val parser = IncomingParser(
            GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .registerDiscordApiTypeAdapters()
                .create(),
        )

        private lateinit var gateway: StoreGatewayConnection

        private val scheduler = storeTestScheduler("gateway-routing-store-thread")

        @JvmStatic
        @BeforeClass
        fun initialize() {
            val directory = Files.createTempDirectory("gateway-dispatch-routing-test").toFile()
            // This used to be a thread-per-action executor that blocked on `join()`. It deadlocked
            // the moment a dispatch was scheduled from inside `ObservationDeck.connect`: the fresh
            // thread's `onDispatchEnded` needs the deck monitor its blocked caller still holds.
            val stream = StoreStream.initializeForTesting(
                directory,
                memoryPreferences(),
                memoryPreferences(),
                scheduler,
            )
            // The socket is never attached: handleDispatch only needs the subjects.
            gateway = StoreGatewayConnection(stream, Clock { 0 })
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
                    "getBoolean", "getInt", "getLong", "getFloat", "getString", "getStringSet" -> values[args[0]] ?: args[1]
                    "contains" -> values.containsKey(args[0])
                    "edit" -> editor
                    else -> null
                }
            } as SharedPreferences
        }
    }
}
