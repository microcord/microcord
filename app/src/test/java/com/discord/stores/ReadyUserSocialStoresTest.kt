package com.discord.stores

import com.discord.api.presence.ClientStatus
import com.discord.api.presence.ClientStatuses
import com.discord.api.user.User as ApiUser
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelCustomStatusSetting
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.domain.ModelUserSettings
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
        val requested = mutableListOf<Set<Long>>()
        val store = StoreUser(
            dispatcher = dispatcher,
            observationDeck = deck,
            meCache = cache,
            requestUsers = { ids, _ -> requested += ids },
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val snapshots = mutableListOf<Map<Long, com.discord.models.user.User>>()
        store.observeAllUsers().subscribe(snapshots::add)

        val payload = readyPayload()
        dispatcher.schedule { store.handleConnectionOpen(payload) }
        scheduler.triggerActions()
        val first = store.getUsers()

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
        assertEquals(listOf(setOf(99L)), requested)
        assertEquals(3, snapshots.size)
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
    fun mutationFromAnotherThreadIsRejectedBeforeStateChanges() {
        val deck = ObservationDeck()
        val store = StoreUserRelationships(deck, Persister("test-thread-${System.nanoTime()}", mapOf(-1L to -1)))
        val failure = AtomicReference<Throwable>()
        Thread {
            failure.set(runCatching { store.handleRelationshipAdd(ModelUserRelationship(1, 1)) }.exceptionOrNull())
        }.apply { start(); join() }

        assertEquals(IllegalStateException::class.java, failure.get()?.javaClass)
        assertEquals(StoreUserRelationships.UserRelationshipsState.Unloaded, store.getRelationshipsState())
    }

    private fun readyPayload(): ModelPayload {
        val json = requireNotNull(javaClass.classLoader?.getResource("ready/ready-envelope.json")).readText()
        return gson.fromJson(json, ModelPayload::class.java)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun initializePersister() {
            Persister.init(Files.createTempDirectory("ready-social-stores").toFile(), Observable.never())
        }
    }
}
