package com.discord.stores

import com.discord.api.guild.Guild
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreLurkingTest {
    private val directory = Files.createTempDirectory("store-lurking").toFile()

    @Before fun setUp() = Persister.init(directory)
    @After fun tearDown() {
        directory.deleteRecursively()
    }

    @Test fun lurkingRequiresBothPreviewStateAndNoMembership() {
        assertTrue(StoreLurking.isLurking(false, 5, setOf(5)))
        assertFalse(StoreLurking.isLurking(true, 5, setOf(5)))
        assertFalse(StoreLurking.isLurking(false, 5, setOf(6)))
    }

    @Test fun queuedRequestReplaysOnReadyAndGatewayRemovalEmitsItsIdLifecycle() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        val selected = StoreGuildSelected(dispatcher, deck)
        val channels = StoreChannels(dispatcher, deck, isAuthenticated = { true })
        val store = StoreLurking(guilds, selected, channels, dispatcher)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(users, guilds, selected, channels), deck))

        val emissions = mutableListOf<Set<Long>>()
        store.getLurkingGuildIds().subscribe(emissions::add)

        store.startLurkingAndNavigate(5, null)
        scheduler.triggerActions()
        assertEquals(listOf(emptySet<Long>()), emissions)

        dispatcher.schedule { store.handleConnectionOpen(ModelPayload().apply { sessionId = "session" }) }
        scheduler.triggerActions()
        assertEquals(setOf(5L), store.getLurkingGuildIdsSync())

        dispatcher.schedule {
            store.handleGuildAdd(guild(5))
            store.handleGuildRemove(guild(5))
        }
        scheduler.triggerActions()

        assertEquals(listOf(emptySet(), setOf(5L), emptySet()), emissions)
    }

    private fun guild(id: Long): Guild = gson.fromJson(
        """{"id":$id,"name":"Preview","owner_id":9,"unavailable":false,"features":[]}""",
        Guild::class.java,
    )

    private companion object {
        val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()
    }
}
