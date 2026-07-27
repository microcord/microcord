package com.discord.stores

import android.content.SharedPreferences
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.persister.Persister
import com.discord.utilities.uri.UriHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreMaskedLinksTest {

    @After
    fun tearDown() {
        SharedPreferencesProvider.setInstanceForTesting(null)
        UriHandler.trustedDomainResolver = null
        UriHandler.trustDomainHandler = null
    }

    @Test
    fun initRestoresDomainsAndConnectsUriHandler() {
        val preferences = memoryPreferences()
        preferences.edit()
            .putStringSet(StoreMaskedLinks.USER_TRUSTED_DOMAINS_CACHE_KEY, setOf("saved.example"))
            .commit()
        SharedPreferencesProvider.setInstanceForTesting(preferences)

        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        val channels = StoreChannels(dispatcher, deck)
        val permissions = StorePermissions(users, channels, guilds, deck)
        val guildSelected = StoreGuildSelected(dispatcher, deck)
        val channelsSelected = StoreChannelsSelected(channels, permissions, guildSelected, dispatcher, deck)
        val relationships = StoreUserRelationships(deck)
        val store = StoreMaskedLinks(dispatcher, channelsSelected, relationships)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

        dispatcher.schedule { store.init(testContext()) }
        scheduler.triggerActions()

        assertTrue(UriHandler.trustedDomainResolver != null)
        assertTrue(UriHandler.trustDomainHandler != null)
        assertEquals(setOf("saved.example"), store.getUserTrustedDomains())
    }

    private fun memoryPreferences(): SharedPreferences {
        val values = linkedMapOf<String, Any?>()
        lateinit var editor: Any
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putStringSet" -> editor.also { values[args[0] as String] = args[1] }
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
                "getStringSet" -> values[args[0]] ?: args[1]
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
    }

    companion object {
        private val directory = Files.createTempDirectory("masked-links")

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
