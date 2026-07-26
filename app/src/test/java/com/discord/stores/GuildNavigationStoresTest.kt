package com.discord.stores

import com.discord.api.guild.Guild as ApiGuild
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelGuildFolder
import com.discord.models.domain.ModelPayload
import com.discord.models.guild.Guild
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import rx.schedulers.TestScheduler

class GuildNavigationStoresTest {
    private val directory = Files.createTempDirectory("guild-navigation-stores").toFile()

    @Before fun setUp() = Persister.init(directory)
    @After fun tearDown() {
        directory.deleteRecursively()
    }

    @Test fun selectionRecencyReadyFilteringAndPersistence() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreGuildSelected(dispatcher, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

        (1L..6L).forEach(store::set)
        store.set(3L)
        scheduler.triggerActions()
        assertEquals(listOf(3L, 6L, 5L, 4L, 2L), store.getRecentlySelectedGuildIds())

        dispatcher.schedule {
            store.handleConnectionOpen(ModelPayload().withGuilds(listOf(apiGuild(2), apiGuild(5))))
        }
        scheduler.triggerActions()
        assertEquals(listOf(5L, 2L), store.getRecentlySelectedGuildIds())

        Persister.init(directory)
        val restored = StoreGuildSelected(dispatcher, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(restored), deck))
        dispatcher.schedule { restored.restore() }
        scheduler.triggerActions()
        assertEquals(listOf(5L, 2L), restored.getRecentlySelectedGuildIds())
    }

    @Test fun folderValidationAndFallbackOrder() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreGuildsSorted(deck, dispatcher, scheduler)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        store.subscribeToStores()
        val guilds = listOf(
            Guild(id = 1, name = "Alpha"), Guild(id = 2, name = "Beta"),
            Guild(id = 3, name = "Lurking"), Guild(id = 4, name = "Delta"),
        ).associateBy(Guild::id)

        dispatcher.schedule {
            store.update(
                guilds, emptySet(), setOf(3),
                listOf(ModelGuildFolder(10, listOf(1, 999, 3), name = "Folder"), ModelGuildFolder(null, listOf(2))),
                setOf(2), mapOf(1L to 100L, 2L to 300L, 4L to 200L),
            )
        }
        scheduler.triggerActions()
        assertEquals(listOf(3L, 4L, 1L, 2L), store.getOrderedGuilds().keys.toList())
        assertEquals(listOf(1L), (store.getEntries()[2] as StoreGuildsSorted.Entry.Folder).guilds.map(Guild::id))

        dispatcher.schedule {
            store.update(guilds - 3L, emptySet(), emptySet(), emptyList(), setOf(2), mapOf(1L to 100L, 2L to 300L, 4L to 200L))
        }
        scheduler.triggerActions()
        assertEquals(
            "rebuilds are throttled to one per second, so the second update is still pending",
            listOf(3L, 4L, 1L, 2L),
            store.getOrderedGuilds().keys.toList(),
        )

        scheduler.advanceTimeBy(1, TimeUnit.SECONDS)
        assertEquals(listOf(4L, 1L, 2L), store.getOrderedGuilds().keys.toList())
    }

    @Test fun userReorderAppliesWithoutWaitingForTheRebuildThrottle() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreGuildsSorted(deck, dispatcher, scheduler)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        store.subscribeToStores()
        val one = Guild(id = 1, name = "Alpha")
        val two = Guild(id = 2, name = "Beta")

        dispatcher.schedule {
            store.update(mapOf(1L to one, 2L to two), emptySet(), emptySet(), emptyList(), emptySet(), emptyMap())
        }
        scheduler.triggerActions()
        assertEquals(listOf(1L, 2L), store.getOrderedGuilds().keys.toList())

        store.setPositions(
            listOf(StoreGuildsSorted.Entry.SingletonGuild(two), StoreGuildsSorted.Entry.SingletonGuild(one)),
        )
        scheduler.triggerActions()

        assertEquals(listOf(2L, 1L), store.getOrderedGuilds().keys.toList())
    }

    @Test fun expandedFoldersPersist() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreExpandedGuildFolders(dispatcher, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

        store.openFolder(10)
        store.openFolder(20)
        store.closeFolder(10)
        scheduler.triggerActions()

        Persister.init(directory)
        val restored = StoreExpandedGuildFolders(dispatcher, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(restored), deck))
        dispatcher.schedule { restored.restore() }
        scheduler.triggerActions()
        assertEquals(setOf(20L), restored.getOpenFolderIds())
    }

    private fun apiGuild(id: Long) = gson.fromJson(
        """{"id":$id,"name":"Guild $id","owner_id":1,"unavailable":false,"features":[]}""",
        ApiGuild::class.java,
    )

    companion object {
        private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters().create()
    }
}
