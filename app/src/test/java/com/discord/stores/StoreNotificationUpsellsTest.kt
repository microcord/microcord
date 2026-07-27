package com.discord.stores

import android.content.SharedPreferences
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.cache.SharedPreferencesProvider
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreNotificationUpsellsTest {
    private val values = mutableMapOf<String, Any?>()
    private val scheduler = TestScheduler()
    private val dispatcher = Dispatcher(scheduler)
    private val deck = ObservationDeck()

    @Before
    fun setUp() {
        setSessionDurablePreferences(preferences(values))
    }

    @After
    fun tearDown() {
        setSessionDurablePreferences(null)
    }

    @Test
    fun dismissalIsImmediatelySessionDurable() {
        val store = StoreNotificationUpsells(dispatcher, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

        assertFalse(store.getPushNotificationsUpsellDismissed())
        store.dismissPushNotificationsUpsell()

        assertEquals(true, values["PUSH_NOTIFICATIONS_DISMISS_UPSELL"])
        assertTrue(store.getPushNotificationsUpsellDismissed())
        assertTrue(StoreNotificationUpsells(dispatcher, deck).getPushNotificationsUpsellDismissed())
    }

    @Test
    fun dismissalPublishesAfterDispatchAndDeduplicates() {
        val store = StoreNotificationUpsells(dispatcher, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val observed = mutableListOf<Boolean>()
        store.observePushNotificationUpsellDismissed().subscribe(observed::add)

        assertEquals(listOf(false), observed)
        store.dismissPushNotificationsUpsell()
        assertEquals(listOf(false), observed)

        scheduler.triggerActions()
        assertEquals(listOf(false, true), observed)

        store.dismissPushNotificationsUpsell()
        scheduler.triggerActions()
        assertEquals(listOf(false, true), observed)
    }

    private fun setSessionDurablePreferences(preferences: SharedPreferences?) {
        SharedPreferencesProvider::class.java.getDeclaredField("prefsSessionDurable").apply {
            isAccessible = true
            set(SharedPreferencesProvider, preferences)
        }
    }

    private fun preferences(values: MutableMap<String, Any?>): SharedPreferences {
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "putBoolean" -> proxy.also { values[arguments!![0] as String] = arguments[1] as Boolean }
                "apply" -> null
                "commit" -> true
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor

        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getBoolean" -> values[arguments!![0] as String] as? Boolean ?: arguments[1] as Boolean
                "edit" -> editor
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences
    }
}
