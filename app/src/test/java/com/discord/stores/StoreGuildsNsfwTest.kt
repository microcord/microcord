package com.discord.stores

import android.content.SharedPreferences
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.cache.SharedPreferencesProvider
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreGuildsNsfwTest {
    @After fun tearDown() = SharedPreferencesProvider.setInstanceForTesting(null)

    @Test fun initAllowSnapshotObservationAndPersistenceMatchReference() {
        val preferences = memoryPreferences()
        preferences.edit().putStringSet("GUILDS_ALLOWED_KEY", setOf("7", "invalid")).commit()
        SharedPreferencesProvider.setInstanceForTesting(preferences)

        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val channels = StoreChannels(dispatcher, deck)
        val store = StoreGuildsNsfw(dispatcher, channels, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

        dispatcher.schedule { store.init(testContext()) }
        scheduler.triggerActions()

        assertTrue(store.isGuildNsfwGateAgreed(7))
        assertFalse(store.isGuildNsfwGateAgreed(8))
        val observed = mutableListOf<Boolean>()
        store.observeIsGuildNsfwGateAgreed(8).subscribe(observed::add)

        store.allow(8)
        scheduler.triggerActions()

        assertEquals(listOf(false, true), observed)
        assertEquals(setOf("7", "8"), preferences.getStringSet("GUILDS_ALLOWED_KEY", emptySet()))
        assertEquals(
            listOf(Dispatcher::class.java, StoreChannels::class.java, ObservationDeck::class.java),
            StoreGuildsNsfw::class.java.constructors.single().parameterTypes.toList(),
        )
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
}
