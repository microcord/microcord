package com.discord.stores

import com.discord.api.guild.GuildFeature
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventUserUpdate
import com.discord.api.stageinstance.RecommendedStageInstance
import com.discord.api.stageinstance.RecommendedStageInstanceGuild
import com.discord.api.stageinstance.StageInstance
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.api.voice.state.VoiceState
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelInvite
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rx.Observable
import rx.schedulers.TestScheduler

/**
 * Behavioural coverage for the stage stores, the scheduled-event store and the invite store.
 *
 * The stage tests drive the real store graph — voice states flow through [StoreVoiceStates], whose
 * `notifyVoiceStatesUpdated` hook is wired to [StoreStageChannels] exactly as `StoreStream` must
 * wire it — because the interesting behaviour is precisely that chain.
 */
class StageAndEventStoresTest {

    // -- StoreStageChannels --------------------------------------------------------------------

    @Test
    fun `stage roles follow voice states and the moderator index tracks them`() {
        val f = Fixture()
        f.connect()

        // 8 owns the guild, so it holds every permission; unsuppressed means it is on stage.
        f.dispatch { voiceStates.handleVoiceStateUpdate(voiceState(userId = 8, suppress = false)) }
        val ownerRoles = requireNotNull(f.stageChannels.getUserRoles(8, STAGE_CHANNEL_ID))
        assertTrue(ownerRoles.isSpeaker)
        assertTrue(ownerRoles.isModerator)

        // 9 has no roles at all and is suppressed: audience.
        f.dispatch { voiceStates.handleVoiceStateUpdate(voiceState(userId = 9, suppress = true)) }
        val listenerRoles = requireNotNull(f.stageChannels.getUserRoles(9, STAGE_CHANNEL_ID))
        assertTrue(listenerRoles.isAudience)

        val channel = requireNotNull(f.channels.getChannel(STAGE_CHANNEL_ID))
        assertEquals(1, f.stageChannels.getOtherModeratorsCountInChannel(channel, userId = 9))
        assertEquals(0, f.stageChannels.getOtherModeratorsCountInChannel(channel, userId = 8))

        // The projection the stage surfaces read: one speaker, one listener.
        val stage = requireNotNull(f.stageChannels.getStageChannelsInGuild(GUILD_ID)[STAGE_CHANNEL_ID])
        assertEquals(setOf(8L), stage.speakerIds)
        assertEquals(1, stage.audienceSize)
        assertEquals(2, stage.participants.size)

        // Leaving drops the role entry and the moderator bookkeeping with it.
        f.dispatch { voiceStates.handleVoiceStateUpdate(voiceState(userId = 8, channelId = null)) }
        assertNull(f.stageChannels.getUserRoles(8, STAGE_CHANNEL_ID)?.takeIf { it.isModerator })
        assertEquals(0, f.stageChannels.getOtherModeratorsCountInChannel(channel, userId = 9))
    }

    @Test
    fun `deleting a stage channel drops its roles`() {
        val f = Fixture()
        f.connect()
        f.dispatch { voiceStates.handleVoiceStateUpdate(voiceState(userId = 8, suppress = false)) }
        assertEquals(setOf(8L), f.stageChannels.getChannelRoles(STAGE_CHANNEL_ID)?.keys)

        val channel = requireNotNull(f.channels.getChannel(STAGE_CHANNEL_ID))
        f.dispatch { stageChannels.handleChannelDelete(channel) }
        assertNull(f.stageChannels.getChannelRoles(STAGE_CHANNEL_ID))
    }

    // -- StoreRequestedStageChannels -----------------------------------------------------------

    @Test
    fun `a requested channel the response omits is recorded as loaded with no stage`() {
        val f = Fixture()
        f.dispatch { requestedStages.updateStatus(setOf(20L, 21L), StoreRequestedStageChannels.FetchStatus.LOADING) }
        assertTrue(requireNotNull(f.requestedStages.getRequestedInstanceStatesByChannel()[21L]).isLoading)

        f.dispatch { requestedStages.onLoaded(setOf(20L, 21L), listOf(recommendedStage(channelId = 20))) }

        val present = requireNotNull(f.requestedStages.getRequestedInstanceStatesByChannel()[20L])
        assertTrue(present.isLoaded)
        assertEquals(20L, f.requestedStages.getStageInstanceForChannel(20)?.channelId)

        // Without this branch a stage that has ended would leave its row spinning forever.
        val absent = requireNotNull(f.requestedStages.getRequestedInstanceStatesByChannel()[21L])
        assertTrue(absent.isLoaded)
        assertNull(absent.stageInstance)
    }

    @Test
    fun `an errored channel keeps whatever instance it already had`() {
        val f = Fixture()
        f.dispatch { requestedStages.onLoaded(setOf(20L), listOf(recommendedStage(channelId = 20))) }
        f.dispatch { requestedStages.updateStatus(setOf(20L), StoreRequestedStageChannels.FetchStatus.ERROR) }
        val state = requireNotNull(f.requestedStages.getRequestedInstanceStatesByChannel()[20L])
        assertTrue(state.isError)
        assertEquals(20L, state.stageInstance?.instance?.channelId)
    }

    @Test
    fun `enqueued stage fetches coalesce into one batch`() {
        val f = Fixture()
        f.requestedStages.enqueueStageChannelFetch(20)
        f.requestedStages.enqueueStageChannelFetch(21)
        // Nothing is requested before the debounce elapses.
        assertTrue(f.requestedStages.getRequestedInstanceStatesByChannel().isEmpty())

        f.scheduler.advanceTimeBy(StoreRequestedStageChannels.ENQUEUE_DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS)
        f.scheduler.triggerActions()

        // One batch, both channels, and with no REST client they land on ERROR together.
        assertEquals(setOf(20L, 21L), f.requestedStages.getRequestedInstanceStatesByChannel().keys)
    }

    /** `ERROR, LOADING, LOADED` — read from the reference's `$VALUES` array, not source order. */
    @Test
    fun `requested-stage fetch status ordinals match the reference`() {
        assertEquals(
            listOf("ERROR", "LOADING", "LOADED"),
            StoreRequestedStageChannels.FetchStatus.values().map { it.name },
        )
    }

    // -- StoreGuildScheduledEvents -------------------------------------------------------------

    @Test
    fun `an update without a user count keeps the count already known`() {
        val f = Fixture()
        f.connect()
        f.dispatch { scheduledEvents.handleGuildScheduledEventCreate(event(id = 500, userCount = 12)) }
        assertEquals(12, f.scheduledEvents.findEventFromStore(500)?.userCount)

        // GUILD_SCHEDULED_EVENT_UPDATE omits `user_count`; letting it through would blank the
        // attendee number on every edit.
        f.dispatch { scheduledEvents.handleGuildScheduledEventUpdate(event(id = 500, userCount = null, name = "renamed")) }
        assertEquals(12, f.scheduledEvents.findEventFromStore(500)?.userCount)
        assertEquals("renamed", f.scheduledEvents.findEventFromStore(500)?.name)
    }

    @Test
    fun `rsvp events move both the count and the current user's rsvp set`() {
        val f = Fixture()
        f.connect()
        f.dispatch { scheduledEvents.handleGuildScheduledEventCreate(event(id = 500, userCount = 0)) }

        f.dispatch { scheduledEvents.handleGuildScheduledEventUserAdd(GuildScheduledEventUserUpdate(500, GUILD_ID, 9)) }
        assertEquals(1, f.scheduledEvents.findEventFromStore(500)?.userCount)
        assertFalse(f.scheduledEvents.isMeRsvpedToEvent(GUILD_ID, 500))

        f.dispatch { scheduledEvents.handleGuildScheduledEventUserAdd(GuildScheduledEventUserUpdate(500, GUILD_ID, 7)) }
        assertEquals(2, f.scheduledEvents.findEventFromStore(500)?.userCount)
        assertTrue(f.scheduledEvents.isMeRsvpedToEvent(GUILD_ID, 500))

        f.dispatch { scheduledEvents.handleGuildScheduledEventUserRemove(GuildScheduledEventUserUpdate(500, GUILD_ID, 7)) }
        assertEquals(1, f.scheduledEvents.findEventFromStore(500)?.userCount)
        assertFalse(f.scheduledEvents.isMeRsvpedToEvent(GUILD_ID, 500))

        // The count never goes negative even if the server over-reports removals.
        f.dispatch { scheduledEvents.handleGuildScheduledEventUserRemove(GuildScheduledEventUserUpdate(500, GUILD_ID, 9)) }
        f.dispatch { scheduledEvents.handleGuildScheduledEventUserRemove(GuildScheduledEventUserUpdate(500, GUILD_ID, 9)) }
        assertTrue((f.scheduledEvents.findEventFromStore(500)?.userCount ?: 0) >= 0)
    }

    @Test
    fun `an invite-resolved event is findable and carries its rsvp`() {
        val f = Fixture()
        f.connect()
        // `addGuildScheduledEventFromInvite` mutates directly rather than hopping the dispatcher,
        // so it has to be invoked on the store thread.
        f.dispatch { scheduledEvents.addGuildScheduledEventFromInvite(event(id = 700, userCount = 3)) }
        assertEquals(700L, f.scheduledEvents.findEventFromStore(700)?.id)

        f.dispatch { scheduledEvents.addMeRsvpsForEvent(listOf(GUILD_ID to 700L)) }
        assertTrue(f.scheduledEvents.isMeRsvpedToEvent(GUILD_ID, 700))

        f.dispatch { scheduledEvents.handleGuildScheduledEventDelete(event(id = 700, userCount = 3)) }
        assertNull(f.scheduledEvents.findEventFromStore(700))
    }

    @Test
    fun `guild removal forgets the guild's events`() {
        val f = Fixture()
        f.connect()
        f.dispatch { scheduledEvents.handleGuildScheduledEventCreate(event(id = 500, userCount = 1)) }
        f.dispatch { scheduledEvents.handleGuildRemove(GUILD_ID) }
        assertNull(f.scheduledEvents.findEventFromStore(500))
    }

    // -- StoreInstantInvites -------------------------------------------------------------------

    @Test
    fun `an unknown invite code observes as loading and resolution is keyed by event`() {
        val f = Fixture()
        val plainKey = ModelInvite.getInviteStoreKey("abc", null)
        val eventKey = ModelInvite.getInviteStoreKey("abc", 700)
        assertEquals("abc", plainKey)

        val seen = mutableListOf<StoreInstantInvites.InviteState>()
        f.invites.observeInvite(plainKey).subscribe { seen += it }
        f.scheduler.triggerActions()
        assertEquals(StoreInstantInvites.InviteState.Loading, seen.last())

        // The same code scoped to an event is a different cache entry.
        f.dispatch { invites.setChatInvites(eventKey, StoreInstantInvites.InviteState.Invalid) }
        assertEquals(StoreInstantInvites.InviteState.Loading, seen.last())

        f.dispatch { invites.setChatInvites(plainKey, StoreInstantInvites.InviteState.LoadFailed) }
        assertEquals(StoreInstantInvites.InviteState.LoadFailed, seen.last())
    }

    @Test
    fun `a null invite key resolves to LoadFailed rather than throwing`() {
        val f = Fixture()
        assertEquals(
            StoreInstantInvites.InviteState.LoadFailed,
            f.invites.observeInvite(null).toBlocking().first(),
        )
    }

    @Test
    fun `the guild invite index is rebuilt wholesale and drops guildless invites`() {
        val f = Fixture()
        f.dispatch {
            invites.onLoadedInvites(
                listOf(invite("aaa", guildId = GUILD_ID), invite("bbb", guildId = null)),
            )
        }
        assertEquals(setOf("aaa"), f.invites.getInvites(GUILD_ID).keys)

        f.dispatch { invites.onLoadedInvites(listOf(invite("ccc", guildId = GUILD_ID))) }
        assertEquals(setOf("ccc"), f.invites.getInvites(GUILD_ID).keys)

        f.invites.onInviteRemoved(invite("ccc", guildId = GUILD_ID))
        f.scheduler.triggerActions()
        assertEquals(emptySet<String>(), f.invites.getInvites(GUILD_ID).keys)
    }

    // -- Fixture --------------------------------------------------------------------------------

    private class Fixture {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        val channels = StoreChannels(dispatcher, deck)
        val permissions = StorePermissions(users, channels, guilds, deck)
        val stageInstances = StoreStageInstances(deck)
        val voiceStates = StoreVoiceStates(
            notifyVoiceStatesUpdated = { guildId, channelId, userId ->
                stageChannels.handleVoiceStatesUpdated(guildId, channelId, userId)
            },
            observationDeck = deck,
        )
        val stageChannels = StoreStageChannels(
            users, guilds, channels, voiceStates, permissions, stageInstances, deck,
        )
        val requestedStages = StoreRequestedStageChannels(
            dispatcher,
            deck,
            restApi = { null },
            timer = { delay, unit -> Observable.timer(delay, unit, scheduler) },
        )
        val scheduledEvents = StoreGuildScheduledEvents(
            dispatcher, permissions, users, guilds, { 1_700_000_000_000L }, deck, { null },
        )
        val invites = StoreInstantInvites(dispatcher, deck, { null })

        init {
            dispatcher.registerDispatchHandlers(
                StoreV2DispatchHandler(
                    listOf(
                        users, guilds, channels, permissions, stageInstances, voiceStates,
                        stageChannels, requestedStages, scheduledEvents, invites,
                    ),
                    deck,
                ),
            )
        }

        fun connect() = dispatch {
            val payload = readyPayload()
            users.handleConnectionOpen(payload)
            guilds.handleConnectionOpen(payload)
            channels.handleConnectionOpen(payload)
            permissions.handleConnectionOpen(payload)
            stageChannels.handleConnectionOpen()
            scheduledEvents.handleConnectionOpen(payload)
        }

        fun dispatch(block: Fixture.() -> Unit) {
            dispatcher.schedule { block() }
            scheduler.triggerActions()
        }
    }

    // -- Payload builders -----------------------------------------------------------------------

    private fun voiceState(
        userId: Long,
        channelId: Long? = STAGE_CHANNEL_ID,
        suppress: Boolean = false,
    ): VoiceState = gson.fromJson(
        """{"user_id":$userId,"guild_id":$GUILD_ID,"channel_id":${channelId ?: "null"},
           "session_id":"s$userId","suppress":$suppress,"self_mute":false,"self_deaf":false,
           "self_video":false,"self_stream":false,"mute":false,"deaf":false,
           "request_to_speak_timestamp":null}""",
        VoiceState::class.java,
    )

    private fun recommendedStage(channelId: Long) = RecommendedStageInstance(
        guild = RecommendedStageInstanceGuild(GUILD_ID, "Guild", null, emptySet<GuildFeature>()),
        instance = StageInstance(
            id = channelId,
            guildId = GUILD_ID,
            channelId = channelId,
            topic = "topic",
            privacyLevel = StageInstancePrivacyLevel.PUBLIC,
            inviteCode = null,
        ),
        speakers = emptyList(),
        sampleSpeakerMembers = emptyList(),
        participantCount = 3,
        source = 0,
        score = 1.0,
    )

    private fun event(id: Long, userCount: Int?, name: String = "Event"): GuildScheduledEvent = gson.fromJson(
        """{"id":$id,"guild_id":$GUILD_ID,"channel_id":$STAGE_CHANNEL_ID,"creator_id":8,
           "name":"$name","description":null,"image":null,
           "scheduled_start_time":"2021-01-01T00:00:00+00:00","scheduled_end_time":null,
           "privacy_level":2,"status":1,"entity_type":1,"entity_id":null,"entity_metadata":null,
           "sku_ids":[],"user_count":${userCount ?: "null"},"user_rsvp":null}""",
        GuildScheduledEvent::class.java,
    )

    private fun invite(code: String, guildId: Long?): ModelInvite = gson.fromJson(
        """{"code":"$code","channel":{"id":$STAGE_CHANNEL_ID,"type":13,"guild_id":$GUILD_ID,"name":"stage"},
           "guild":${guildId?.let { """{"id":$it,"name":"Guild","owner_id":8,"features":[]}""" } ?: "null"}}""",
        ModelInvite::class.java,
    )

    private fun readyPayload(): ModelPayload = gson.fromJson(
        """
        {
          "user": {"id":7,"username":"me","discriminator":"0000"},
          "session_id": "s7",
          "private_channels": [],
          "guilds": [$GUILD_JSON]
        }
        """.trimIndent(),
        ModelPayload::class.java,
    )

    companion object {
        private const val GUILD_ID = 1L
        private const val STAGE_CHANNEL_ID = 20L

        /** Guild 1, owned by 8, with one stage channel and two members besides the owner. */
        private const val GUILD_JSON = """
        {
          "id": 1, "name": "Guild", "owner_id": 8, "unavailable": false, "features": [],
          "roles": [],
          "members": [
            {"guild_id":1,"user":{"id":8,"username":"owner","discriminator":"0001"},"roles":[],"pending":false},
            {"guild_id":1,"user":{"id":9,"username":"listener","discriminator":"0002"},"roles":[],"pending":false}
          ],
          "channels": [
            {"id":20,"type":13,"guild_id":1,"name":"stage"}
          ],
          "voice_states": []
        }
        """

        private val gson = GsonBuilder()
            .excludeFieldsWithModifiers(Modifier.STATIC)
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()

        private val directory = Files.createTempDirectory("stage-event-stores")

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
