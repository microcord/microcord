package com.discord.stores

import android.util.Log
import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityAssets
import com.discord.api.activity.ActivityParty
import com.discord.api.activity.ActivityType
import com.discord.api.presence.ClientStatus
import com.discord.api.presence.ClientStatuses
import com.discord.api.user.User as ApiUser
import com.discord.app.AppLog
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelCustomStatusSetting
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.domain.ModelUserSettings
import com.discord.models.message.Message
import com.discord.models.user.CoreUser
import com.discord.nullserializable.NullSerializable
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rx.Observable
import rx.schedulers.TestScheduler

class ReadyUserSocialStoresTest {
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerDiscordApiTypeAdapters()
        .create()

    @Test
    fun readyHydrationPublishesStableUserSnapshotsAndPersistsMeExactly() {
        val scheduler = TestScheduler()
        val deck = ObservationDeck()
        val dispatcher = Dispatcher(scheduler)
        val cache = Persister("test-me-${System.nanoTime()}", StoreUser.EMPTY_ME_USER)
        val requested = mutableListOf<Long>()
        val store = StoreUser(
            dispatcher = dispatcher,
            observationDeck = deck,
            meCache = cache,
            requestUser = { id, _ -> requested += id },
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val snapshots = mutableListOf<Map<Long, com.discord.models.user.User>>()
        store.observeAllUsers().subscribe(snapshots::add)

        val payload = readyPayload()
        dispatcher.schedule { store.handleConnectionOpen(payload) }
        scheduler.triggerActions()
        val first = store.getUsers()
        val initialMe = store.getMe()

        val update = gson.fromJson(
            "{\"id\":42,\"username\":\"renamed\",\"discriminator\":\"0001\",\"avatar\":null}",
            ApiUser::class.java,
        )
        dispatcher.schedule { store.handleUserUpdated(update) }
        scheduler.triggerActions()
        store.fetchUsers(listOf(42, 99))
        scheduler.triggerActions()

        assertEquals("ready-user", first[42]?.username)
        assertEquals("renamed", store.getMe().username)
        assertEquals(null, store.getMe().avatar)
        assertEquals(initialMe.copy(username = "renamed", avatar = null), store.getMe())
        assertEquals(listOf(99L), requested)
        assertEquals(4, snapshots.size)
        assertFalse(store.getMe().toString().contains("auth-secret"))

        Persister.persistAll()
        val restored = StoreUser(
            dispatcher = dispatcher,
            observationDeck = deck,
            meCache = Persister(cache.key, StoreUser.EMPTY_ME_USER),
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(restored), deck))
        dispatcher.schedule { restored.restore() }
        scheduler.triggerActions()
        assertEquals(store.getMe(), restored.getMe())
    }

    @Test
    fun sparseUsersMergeAndMissingRequestsAreDeduplicatedAndSerialized() {
        val scheduler = TestScheduler()
        val deck = ObservationDeck()
        val dispatcher = Dispatcher(scheduler)
        val requested = mutableListOf<Long>()
        val callbacks = mutableListOf<(ApiUser?) -> Unit>()
        val store = StoreUser(
            dispatcher = dispatcher,
            observationDeck = deck,
            meCache = Persister("test-sparse-me-${System.nanoTime()}", StoreUser.EMPTY_ME_USER),
            requestUser = { id, callback -> requested += id; callbacks += callback },
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val complete = ApiUser(
            id = 77,
            username = "complete",
            avatar = NullSerializable.Value("avatar"),
            banner = NullSerializable.Value("banner"),
            discriminator = "1234",
            flags = 7,
            publicFlags = 9,
            bio = NullSerializable.Value("bio"),
        )

        dispatcher.schedule {
            store.handleFriendSuggestionCreate(complete)
            store.handleFriendSuggestionCreate(ApiUser(77, "renamed"))
        }
        scheduler.triggerActions()
        val merged = store.getUsers()[77] as CoreUser
        store.fetchUsers(listOf(88, 88, 89))
        store.fetchUsers(listOf(88))
        scheduler.triggerActions()

        assertEquals("renamed", merged.username)
        assertEquals("avatar", merged.avatar)
        assertEquals("banner", merged.banner)
        assertEquals(1234, merged.discriminator)
        assertEquals(7, merged.flags)
        assertEquals(9, merged.publicFlags)
        assertEquals("bio", merged.bio)
        assertEquals(listOf(88L), requested)
        assertEquals(1, callbacks.size)
        assertTrue(store.isUserFetchPending(88))
        assertTrue(store.isUserFetchPending(89))

        callbacks[0](ApiUser(88, "fetched"))
        scheduler.triggerActions()
        assertEquals(listOf(88L, 89L), requested)
        assertEquals("fetched", store.getUsers()[88]?.username)
        assertFalse(store.isUserFetchPending(88))
        assertTrue(store.isUserFetchPending(89))

        callbacks[1](null)
        scheduler.triggerActions()
        assertFalse(store.isUserFetchPending(89))
        store.fetchUsers(listOf(88, 89))
        scheduler.triggerActions()
        assertEquals(listOf(88L, 89L), requested)
    }

    @Test
    fun urgentMessageSetsCurrentUserFlag() {
        val scheduler = TestScheduler()
        val deck = ObservationDeck()
        val dispatcher = Dispatcher(scheduler)
        val store = StoreUser(
            dispatcher = dispatcher,
            observationDeck = deck,
            meCache = Persister("test-urgent-me-${System.nanoTime()}", StoreUser.EMPTY_ME_USER),
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

        dispatcher.schedule {
            store.handleConnectionOpen(readyPayload())
            store.handleMessageCreateOrUpdate(Message(id = 1, flags = 16))
        }
        scheduler.triggerActions()

        assertEquals(8192, store.getMe().flags and 8192)
    }

    @Test
    fun relationshipsPersistLoadedEmptyAndReturnToUnloadedOnLogout() {
        val scheduler = TestScheduler()
        val deck = ObservationDeck()
        val dispatcher = Dispatcher(scheduler)
        val cache = Persister<Map<Long, Int>>("test-relationships-${System.nanoTime()}", mapOf(-1L to -1))
        val store = StoreUserRelationships(deck, cache)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

        dispatcher.schedule {
            store.handleConnectionOpen(readyPayload())
            store.handleRelationshipAdd(ModelUserRelationship(7, ModelUserRelationship.TYPE_BLOCKED))
        }
        scheduler.triggerActions()
        assertEquals(mapOf(7L to ModelUserRelationship.TYPE_BLOCKED), store.getRelationships())

        Persister.persistAll()
        val restored = StoreUserRelationships(deck, Persister(cache.key, mapOf(-1L to -1)))
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(restored), deck))
        dispatcher.schedule { restored.init() }
        scheduler.triggerActions()
        assertEquals(store.getRelationships(), restored.getRelationships())

        dispatcher.schedule { store.handlePreLogout() }
        scheduler.triggerActions()
        assertEquals(StoreUserRelationships.UserRelationshipsState.Unloaded, store.getRelationshipsState())
        assertEquals(emptyMap<Long, Int>(), store.getRelationships())
    }

    @Test
    fun newestGuildPresenceWinsAndRemovalFallsBack() {
        var now = 1L
        val scheduler = TestScheduler()
        val deck = ObservationDeck()
        val dispatcher = Dispatcher(scheduler)
        val published = mutableListOf<ClientStatus>()
        val store = StoreUserPresence(Clock { now }, deck, publishLocalPresence = { published += it.status })
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

        dispatcher.schedule {
            store.handleConnectionOpen(readyPayload())
            store.handlePresenceUpdate(10, 9, ClientStatus.ONLINE, null, null)
            now = 2
            store.handlePresenceUpdate(20, 9, ClientStatus.IDLE, null, null)
        }
        scheduler.triggerActions()
        val newest = store.getPresences()
        assertEquals(ClientStatus.IDLE, newest[9]?.status)

        dispatcher.schedule { store.handleGuildMemberRemove(9, 20) }
        scheduler.triggerActions()
        assertEquals(ClientStatus.ONLINE, store.getPresences()[9]?.status)
        assertEquals(ClientStatus.IDLE, newest[9]?.status)

        dispatcher.schedule {
            store.handleUserSettingsUpdate(
                ModelUserSettings(
                    status = ClientStatus.DND,
                    customStatus = ModelCustomStatusSetting("working", null, "test", null),
                ),
            )
        }
        scheduler.triggerActions()
        assertEquals(ClientStatus.DND, store.getLocalPresence().status)
        assertEquals("working", store.getLocalPresence().activities?.single()?.state)
        assertEquals(listOf(ClientStatus.DND), published)
    }

    @Test
    fun richActivitiesSortBeforePlainActivities() {
        var now = 1L
        val scheduler = TestScheduler()
        val deck = ObservationDeck()
        val dispatcher = Dispatcher(scheduler)
        val store = StoreUserPresence(Clock { now }, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val plain = activity("plain", 20)
        val richValues = listOf(
            activity("assets", 1, assets = ActivityAssets("large", null, null, null)),
            activity("party", 2, party = ActivityParty("party", null)),
            activity("state", 3, state = "state"),
            activity("details", 4, details = "details"),
        )

        dispatcher.schedule {
            store.handleConnectionOpen(readyPayload())
            store.handlePresenceUpdate(1, 91, ClientStatus.ONLINE, null, listOf(plain) + richValues)
        }
        scheduler.triggerActions()

        assertEquals(richValues.map(Activity::name).reversed() + "plain", store.getPresences()[91]?.activities?.map(Activity::name))
    }

    /**
     * The off-dispatcher guard *reports*; it does not throw.
     *
     * `StoreV2.assertStoreThread` in the reference is
     * `Logger.e(AppLog, "markChanged() should be run from the Store Dispatcher", IllegalStateException(...))`
     * — read straight out of the Vineflower decompile of `com/discord/stores/StoreV2`. So the
     * contract is: no exception reaches the caller, an error carrying the offending stack is
     * logged, and nothing reaches the *published* snapshot, because only `snapshotData()` — which
     * a dispatch runs — republishes it.
     */
    @Test
    fun mutationFromAnotherThreadIsReportedAndNeverPublished() {
        val deck = ObservationDeck()
        val store = StoreUserRelationships(deck, Persister("test-thread-${System.nanoTime()}", mapOf(-1L to -1)))
        val failure = AtomicReference<Throwable>()
        val reported = mutableListOf<AppLog.LoggedItem>()

        val previousPriority = AppLog.minimumPriority
        val previousSink = AppLog.sink
        AppLog.minimumPriority = Log.ERROR
        // Without a sink `AppLog` falls through to logcat, where `Log.getStackTraceString` returns
        // null under `unitTests.isReturnDefaultValues` and the logger itself throws NPE.
        AppLog.sink = { _, _, _, _ -> }
        val subscription = AppLog.observeLogs().subscribe { reported += it }
        try {
            Thread {
                failure.set(runCatching { store.handleRelationshipAdd(ModelUserRelationship(1, 1)) }.exceptionOrNull())
            }.apply { start(); join() }
        } finally {
            subscription.unsubscribe()
            AppLog.minimumPriority = previousPriority
            AppLog.sink = previousSink
        }

        assertNull(failure.get())
        assertTrue(reported.any { it.throwable is IllegalStateException })
        assertEquals(StoreUserRelationships.UserRelationshipsState.Unloaded, store.getRelationshipsState())
    }

    private fun readyPayload(): ModelPayload {
        val json = requireNotNull(javaClass.classLoader?.getResource("ready/ready-envelope.json")).readText()
        return gson.fromJson(json, ModelPayload::class.java)
    }

    private fun activity(
        name: String,
        createdAt: Long,
        assets: ActivityAssets? = null,
        party: ActivityParty? = null,
        state: String? = null,
        details: String? = null,
    ) = Activity(
        name, ActivityType.PLAYING, null, createdAt, null, null, details, state, null, party, assets,
        null, null, null, null, null, null, null,
    )

    companion object {
        @JvmStatic
        @BeforeClass
        fun initializePersister() {
            Persister.init(Files.createTempDirectory("ready-social-stores").toFile(), Observable.never())
        }
    }
}
