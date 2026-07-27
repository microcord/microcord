package com.discord.stores

import com.discord.api.directory.DirectoryEntryEvent
import com.discord.api.directory.DirectoryEntryGuild
import com.discord.api.guild.preview.GuildPreview
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.guildscheduledevent.GuildScheduledEventMeUser
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.utilities.Success
import com.discord.utilities.persister.Persister
import com.discord.widgets.directories.DirectoryStores
import com.discord.widgets.hubs.HubStores
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import rx.Observable
import rx.schedulers.Schedulers
import rx.schedulers.TestScheduler

class StoreDirectoriesTest {

    @Before
    fun setUp() {
        Persister.init(Files.createTempDirectory("store-directories").toFile())
        DirectoryStores.reset()
    }

    @Test
    fun fetchSortsByMemberCountAndPublishesOneStoreSnapshot() {
        val fixture = Fixture(listOf(entry(1L, 10), entry(2L, 50), entry(3L, null)))

        fixture.store.fetchDirectoriesForChannel(CHANNEL_ID)
        fixture.scheduler.triggerActions()

        assertEquals(
            listOf(2L, 1L, 3L),
            (fixture.store.getDirectoriesForChannel(CHANNEL_ID) as Success<List<DirectoryEntryGuild>>)
                .response.map { it.guild.id },
        )
    }

    @Test
    fun addReplacesByGuildIdAndRemoveMutatesOnlyAfterSuccess() {
        val fixture = Fixture(emptyList())
        fixture.store.addServerToDirectory(CHANNEL_ID, entry(1L, 10))
        fixture.store.addServerToDirectory(CHANNEL_ID, entry(1L, 99))
        fixture.store.addServerToDirectory(CHANNEL_ID, entry(2L, 20))
        fixture.scheduler.triggerActions()

        assertEquals(
            listOf(1L to 99, 2L to 20),
            fixture.entries().map { it.guild.id to it.guild.approximateMemberCount },
        )

        fixture.store.removeServerFromDirectory(CHANNEL_ID, 1L)
        fixture.scheduler.triggerActions()
        assertEquals(listOf(2L), fixture.entries().map { it.guild.id })
    }

    @Test
    fun promptAndHeaderFlagsPersistTheirReferenceSemantics() {
        val fixture = Fixture(emptyList())

        assertEquals(false, fixture.store.getAndSetSeenNamePrompt(7L))
        assertEquals(true, fixture.store.getAndSetSeenNamePrompt(7L))
        assertEquals(false, fixture.store.getGuildScheduledEventsHeaderDismissed())
        fixture.store.markGuildScheduledEventsHeaderDismissed()
        assertEquals(true, fixture.store.getGuildScheduledEventsHeaderDismissed())
    }

    @Test
    fun oneInstallationPublishesTheSameStoreToDirectoriesAndHubs() {
        val store = Fixture(emptyList()).store

        DirectoryStores.install(store)

        assertEquals(store, DirectoryStores.directories)
        assertEquals(store, HubStores.directories)
    }

    @Test
    fun hubEventFetchSeedsTheCurrentUsersRsvps() {
        val event = scheduledEvent(guildId = 7L, eventId = 8L)
        val fixture = Fixture(emptyList(), listOf(directoryEvent(event)))

        fixture.store.fetchGuildScheduledEventsForChannel(7L, CHANNEL_ID)
        fixture.scheduler.triggerActions()

        assertEquals(
            listOf(event),
            (fixture.store.getGuildScheduledEventsForChannel(CHANNEL_ID)
                as Success<List<DirectoryEntryEvent>>).response.map { it.guildScheduledEvent },
        )
        assertEquals(setOf(8L), fixture.scheduledEvents.getMeGuildScheduledEventIds(7L))
    }

    private class Fixture(
        private val directoryEntries: List<DirectoryEntryGuild>,
        private val directoryEvents: List<DirectoryEntryEvent> = emptyList(),
    ) {
        val scheduler = TestScheduler()
        private val dispatcher = Dispatcher(scheduler)
        private val deck = ObservationDeck()
        private val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        private val guilds = StoreGuilds(users, dispatcher, deck)
        private val channels = StoreChannels(dispatcher, deck)
        private val permissions = StorePermissions(users, channels, guilds, deck)
        val scheduledEvents = StoreGuildScheduledEvents(
            dispatcher,
            permissions,
            users,
            guilds,
            { 0L },
            deck,
            { null },
        )
        private val api = Proxy.newProxyInstance(
            RestAPIInterface::class.java.classLoader,
            arrayOf(RestAPIInterface::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getDirectoryEntries" -> Observable.just(directoryEntries)
                "getEntryCounts" -> Observable.just(emptyMap<Int, Int>())
                "getDirectoryGuildScheduledEvents" -> Observable.just(directoryEvents)
                "removeServerFromHub" -> Observable.just(Response.success(directoryEntries.firstOrNull()))
                else -> error("Unexpected REST call: ${method.name}")
            }
        } as RestAPIInterface

        val store = StoreDirectories(
            dispatcher,
            deck,
            guilds,
            scheduledEvents,
            api,
            hubEventsEnabled = { true },
            requestScheduler = Schedulers.immediate(),
        )

        init {
            dispatcher.registerDispatchHandlers(
                StoreV2DispatchHandler(listOf(store, scheduledEvents), deck),
            )
        }

        fun entries(): List<DirectoryEntryGuild> =
            (store.getDirectoriesForChannel(CHANNEL_ID) as Success<List<DirectoryEntryGuild>>).response
    }

    private companion object {
        const val CHANNEL_ID = 42L

        fun entry(guildId: Long, members: Int?) = DirectoryEntryGuild(
            directoryChannelId = CHANNEL_ID,
            entityId = guildId,
            authorId = 5L,
            description = "description",
            primaryCategoryId = 0,
            guild = GuildPreview(
                id = guildId,
                name = "Guild $guildId",
                description = null,
                splash = null,
                banner = null,
                icon = null,
                approximatePresenceCount = 0,
                approximateMemberCount = members,
                emojis = emptyList(),
                features = emptyList(),
                featurableInDirectory = true,
            ),
            createdAt = UtcDateTime(0L),
        )

        fun directoryEvent(event: GuildScheduledEvent) = DirectoryEntryEvent(
            directoryChannelId = CHANNEL_ID,
            entityId = event.id,
            authorId = 5L,
            description = "description",
            primaryCategoryId = 0,
            createdAt = UtcDateTime(0L),
            guildScheduledEvent = event,
        )

        fun scheduledEvent(guildId: Long, eventId: Long) = GuildScheduledEvent(
            id = eventId,
            guildId = guildId,
            channelId = null,
            creatorId = null,
            name = "Event",
            description = null,
            image = null,
            scheduledStartTime = UtcDateTime(0L),
            scheduledEndTime = null,
            privacyLevel = StageInstancePrivacyLevel.GUILD_ONLY,
            status = GuildScheduledEventStatus.SCHEDULED,
            entityType = GuildScheduledEventEntityType.EXTERNAL,
            entityId = null,
            entityMetadata = null,
            skuIds = emptyList(),
            userCount = 1,
            userRsvp = GuildScheduledEventMeUser(1L, eventId, null, null),
            guild = null,
        )
    }
}
