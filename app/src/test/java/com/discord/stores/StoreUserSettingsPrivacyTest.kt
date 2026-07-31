package com.discord.stores

import android.content.SharedPreferences
import com.discord.models.domain.Consent
import com.discord.models.domain.Consents
import com.discord.models.domain.ModelUserSettings
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.rest.RestAPI
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import rx.Observable
import rx.schedulers.Schedulers

class StoreUserSettingsPrivacyTest {
    @After
    fun resetRestApi() = RestAPI.reset()

    @Test
    fun privacyStoreResolvesRestApiInstalledAfterConstruction() {
        val expected = Consents(Consent(true, "usage"), Consent(true, "personalization"))
        val store = store()
        RestAPI.install(apiReturning(expected))

        assertEquals(expected, store.observeConsents().toBlocking().single())
    }

    @Test
    fun readyPayloadHydratesEveryPrivacyObservable() {
        val store = store()
        val sources = ModelUserSettings.FriendSourceFlags(all = false, mutualFriends = true, mutualGuilds = false)
        StoreThreadScheduler.bindCurrentThread()

        store.handleConnectionOpen(
            ModelUserSettings(
                explicitContentFilter = ModelUserSettings.EXPLICIT_CONTENT_FILTER_NON_FRIENDS,
                defaultGuildsRestricted = true,
                friendSourceFlags = sources,
                friendDiscoveryFlags = 6,
            ),
        )

        assertEquals(1, store.observeExplicitContentFilter().take(1).toBlocking().single())
        assertEquals(true, store.observeIsDefaultGuildsRestricted().take(1).toBlocking().single())
        assertEquals(sources, store.observeFriendSourceFlags().take(1).toBlocking().single())
        assertEquals(6, store.observeFriendDiscoveryFlags().take(1).toBlocking().single())
    }

    private fun store(): StoreUserSettings {
        val dispatcher = Dispatcher(Schedulers.immediate())
        val observationDeck = ObservationDeck()
        return StoreUserSettings(
            dispatcher,
            StoreAccessibility(dispatcher, observationDeck),
            memoryPreferences(),
        )
    }

    private fun apiReturning(consents: Consents) = Proxy.newProxyInstance(
        RestAPIInterface::class.java.classLoader,
        arrayOf(RestAPIInterface::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getConsents" -> Observable.just(consents)
            "toString" -> "PrivacyRestApi"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as RestAPIInterface

    private fun memoryPreferences() = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getBoolean", "getInt", "getLong", "getFloat", "getString", "getStringSet" -> args[1]
            "contains" -> false
            "getAll" -> emptyMap<String, Any?>()
            else -> null
        }
    } as SharedPreferences
}
