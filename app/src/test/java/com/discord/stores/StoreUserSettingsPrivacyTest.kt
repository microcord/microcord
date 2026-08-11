package com.discord.stores

import android.content.SharedPreferences
import com.discord.models.domain.Consent
import com.discord.models.domain.Consents
import com.discord.models.domain.ModelUserSettings
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreUserSettings.ConsentState
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import rx.Observable
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import discord_protos.discord_users.v1.PreloadedUserSettingsOuterClass.PreloadedUserSettings
import com.google.protobuf.UInt32Value
import com.google.protobuf.BoolValue

class StoreUserSettingsPrivacyTest {
    private val expected = Consents(Consent(true, "usage"), Consent(false, "personalization"))

    @Before
    fun setUp() = Persister.init(Files.createTempDirectory("privacy-settings").toFile(), Observable.never())

    @Test
    fun `rest unavailable then installed retries without another ready`() {
        var api: RestAPIInterface? = null
        val store = store { api }
        val states = mutableListOf<ConsentState>()
        val unavailable = CountDownLatch(1)
        val loaded = CountDownLatch(1)
        store.observeConsentState().subscribe {
            synchronized(states) { states += it }
            if (it is ConsentState.Failed) unavailable.countDown()
            if (it == ConsentState.Loaded(expected)) loaded.countDown()
        }

        assertTrue(unavailable.await(2, TimeUnit.SECONDS))
        assertTrue(synchronized(states) { states.last() } is ConsentState.Failed)
        val installed = apiReturning(Observable.just(expected))
        api = installed
        store.bindApi(installed)

        assertTrue(loaded.await(2, TimeUnit.SECONDS))
        assertEquals(ConsentState.Loaded(expected), synchronized(states) { states.last() })
    }

    @Test
    fun `rest success emits exact consent values`() {
        assertEquals(ConsentState.Loaded(expected), terminal(Observable.just(expected)))
    }

    @Test
    fun `rest 204 or empty is a terminal failure`() {
        val state = terminal(Observable.empty()) as ConsentState.Failed
        assertNull(state.current)
        assertEquals("Privacy consent response was empty.", state.error.message)
    }

    @Test
    fun `http error is a terminal failure with the exact cause`() {
        val error = HttpException(Response.error<Any>(500, "consent unavailable".toResponseBody()))
        val state = terminal(Observable.error(error)) as ConsentState.Failed
        assertNull(state.current)
        assertSame(error, state.error)
    }

    @Test
    fun `failed refresh retains the last successful consent values`() {
        var response: Observable<Consents> = Observable.just(expected)
        val store = store { apiReturning(response) }
        assertEquals(ConsentState.Loaded(expected), terminal(store))

        val error = IllegalArgumentException("refresh failed")
        response = Observable.error(error)
        StoreThreadScheduler.bindCurrentThread()
        store.handleConnectionOpen(settings())
        val state = await(store) { it is ConsentState.Failed } as ConsentState.Failed

        assertEquals(expected, state.current)
        assertSame(error, state.error)
    }

    @Test
    fun `in flight consent fetch survives observer replacement`() {
        val response = PublishSubject.create<Consents>()
        val requested = CountDownLatch(1)
        var requests = 0
        val store = store {
            apiReturning(Observable.defer {
                requests++
                requested.countDown()
                response
            })
        }
        val first = store.observeConsentState().subscribe()
        val loaded = CountDownLatch(1)
        store.observeConsentState().subscribe {
            if (it == ConsentState.Loaded(expected)) loaded.countDown()
        }
        first.unsubscribe()

        assertTrue(requested.await(2, TimeUnit.SECONDS))
        response.onNext(expected)
        response.onCompleted()

        assertEquals(1, requests)
        assertTrue(loaded.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `ready privacy settings are replayed to observers without a consent field`() {
        val store = store { apiReturning(Observable.just(expected)) }
        StoreThreadScheduler.bindCurrentThread()
        store.handleConnectionOpen(settings())

        assertEquals(ModelUserSettings.EXPLICIT_CONTENT_FILTER_NON_FRIENDS, store.observeExplicitContentFilter().take(1).toBlocking().single())
        assertEquals(true, store.observeIsDefaultGuildsRestricted().take(1).toBlocking().single())
        assertEquals(
            ModelUserSettings.FriendSourceFlags(mutualGuilds = true),
            store.observeFriendSourceFlags().take(1).toBlocking().single(),
        )
        assertEquals(6, store.observeFriendDiscoveryFlags().take(1).toBlocking().single())
    }

    private fun terminal(response: Observable<Consents>) = terminal(store { apiReturning(response) })

    private fun terminal(store: StoreUserSettings): ConsentState = await(store) {
        it is ConsentState.Loaded || it is ConsentState.Failed
    }

    private fun await(store: StoreUserSettings, predicate: (ConsentState) -> Boolean): ConsentState {
        val done = CountDownLatch(1)
        var result: ConsentState? = null
        val subscription = store.observeConsentState().subscribe {
            if (predicate(it)) {
                result = it
                done.countDown()
            }
        }
        assertTrue("consent state did not terminate", done.await(2, TimeUnit.SECONDS))
        subscription.unsubscribe()
        return requireNotNull(result)
    }

    private fun settings(): PreloadedUserSettings = PreloadedUserSettings.newBuilder().apply {
        textAndImagesBuilder.explicitContentFilter =
            UInt32Value.of(ModelUserSettings.EXPLICIT_CONTENT_FILTER_NON_FRIENDS)
        privacyBuilder.defaultGuildsRestrictedV2 = BoolValue.of(true)
        privacyBuilder.friendSourceFlags = UInt32Value.of(0b0100)
        privacyBuilder.friendDiscoveryFlags = UInt32Value.of(6)
    }.build()

    private fun store(api: () -> RestAPIInterface?): StoreUserSettings {
        val dispatcher = Dispatcher(Schedulers.immediate())
        return StoreUserSettings(
            dispatcher,
            StoreAccessibility(dispatcher, ObservationDeck()),
            memoryPreferences(),
            api,
        )
    }

    private fun apiReturning(consents: Observable<Consents>) = Proxy.newProxyInstance(
        RestAPIInterface::class.java.classLoader,
        arrayOf(RestAPIInterface::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getConsents" -> consents
            "toString" -> "PrivacyRestApi"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as RestAPIInterface

    private fun memoryPreferences(): SharedPreferences {
        val values = linkedMapOf<String, Any?>()
        lateinit var editor: Any
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putBoolean", "putInt", "putLong", "putFloat", "putString", "putStringSet" ->
                    editor.also { values[args[0] as String] = args[1] }
                "remove" -> editor.also { values.remove(args[0] as String) }
                "clear" -> editor.also { values.clear() }
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
                "getBoolean", "getInt", "getLong", "getFloat", "getString", "getStringSet" ->
                    values[args[0]] ?: args[1]
                "contains" -> values.containsKey(args[0])
                "getAll" -> values.toMap()
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
    }
}
