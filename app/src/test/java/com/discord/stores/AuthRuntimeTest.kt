package com.discord.stores

import android.content.SharedPreferences
import com.discord.api.auth.RequiredAction
import com.discord.api.auth.mfa.MfaMethodType
import com.discord.models.authentication.AuthState
import com.discord.models.domain.auth.ModelLoginResult
import com.discord.restapi.RestAPIBuilder
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.authentication.AuthStateCache
import java.lang.reflect.Proxy
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.schedulers.Schedulers

class AuthRuntimeTest {
    @Test
    fun legacyTokenMigratesAndRequiredActionsPersistWithoutLeakingToken() {
        val preferences = memoryPreferences()
        preferences.edit().putString(AuthStateCache.Keys.TOKEN, "legacy-secret").commit()
        val cache = AuthStateCache(preferences)

        assertEquals(AuthState("legacy-secret", emptySet()), cache.getCachedAuthState())

        val state = AuthState("replacement-secret", setOf(RequiredAction.UPDATE_PASSWORD))
        cache.cacheAuthState(state)
        assertEquals(state, cache.getCachedAuthState())
        assertFalse(state.toString().contains("replacement-secret"))
        assertFalse(preferences.getString(AuthStateCache.Keys.AUTH_STATE, null)!!.contains("legacy-secret"))

        cache.cacheAuthState(null)
        assertNull(cache.getCachedAuthState())
    }

    @Test
    fun loginResultMutatesThroughDispatcherAndRetainsDurableSessionFields() {
        val preferences = memoryPreferences()
        val durablePreferences = memoryPreferences()
        val dispatcher = Dispatcher(Schedulers.immediate())
        dispatcher.registerDispatchHandlers(DispatchHandler {})
        lateinit var store: StoreAuthentication
        store = StoreAuthentication(
            dispatcher,
            preferences,
            durablePreferences,
            onAuthStateChanged = storeCallback@{ store.handleAuthStateInternal(it) },
            onLoginResult = storeCallback@{ store.handleLoginResultInternal(it) },
        )
        store.init()
        store.setFingerprint("fingerprint", false)

        store.handleLoginResult(
            ModelLoginResult(
                ticket = null,
                token = "runtime-secret",
                userSettings = null,
                requiredActions = listOf(RequiredAction.UPDATE_PASSWORD),
            ),
        )

        assertEquals(setOf(RequiredAction.UPDATE_PASSWORD), store.getAuthState()?.requiredActions)
        assertEquals("runtime-secret", store.getAuthState()?.token)
        assertFalse(store.getAuthState().toString().contains("runtime-secret"))
        assertNull(store.getFingerprint())
        assertNull(durablePreferences.getString(StoreAuthentication.CACHE_KEY_FINGERPRINT, null))

        store.loginWithToken(" direct-secret ")
        assertEquals("direct-secret", store.getAuthState()?.token)
        assertTrue(store.getAuthState()?.requiredActions.orEmpty().isEmpty())
        assertFalse(store.getAuthState().toString().contains("direct-secret"))
    }

    /**
     * An MFA ticket is single use, so a second POST always fails. Retrofit's observable is cold:
     * anything in the chain that re-subscribes to it silently re-sends the whole request.
     */
    @Test
    fun mfaVerificationPostsExactlyOnce() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("{\"token\":\"mfa\"}"))
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"code\":60008}"))
            val preferences = memoryPreferences()
            val dispatcher = Dispatcher(Schedulers.immediate())
            dispatcher.registerDispatchHandlers(DispatchHandler {})
            lateinit var store: StoreAuthentication
            store = StoreAuthentication(
                dispatcher,
                preferences,
                memoryPreferences(),
                onAuthStateChanged = storeCallback@{ store.handleAuthStateInternal(it) },
                onLoginResult = storeCallback@{ store.handleLoginResultInternal(it) },
            )
            store.init()
            store.bindApi(
                RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
                    .build(RestAPIInterface::class.java),
            )

            val result = store.authMFA(MfaMethodType.TOTP, "123456", "ticket").toBlocking().single()

            assertEquals("mfa", result.token)
            assertEquals("/api/v9/auth/mfa/totp", server.takeRequest().path)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun authApiFactoryCoversLoginRegistrationMfaVerificationAndLogout() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("{\"token\":\"login\"}"))
            server.enqueue(MockResponse().setBody("{\"token\":\"register\"}"))
            server.enqueue(MockResponse().setBody("{\"token\":\"mfa\"}"))
            server.enqueue(MockResponse().setBody("{\"token\":\"phone\"}"))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val api = RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
                .build(RestAPIInterface::class.java)

            api.postAuthLogin(RestAPIParams.AuthLogin("login", "password", false, null)).toBlocking().single()
            api.postAuthRegister(RestAPIParams.AuthRegister("fingerprint", "user", "email", null, "password", null, null, false, null)).toBlocking().single()
            api.postMFACode(MfaMethodType.TOTP.wireName, RestAPIParams.MFALogin("ticket", "code")).toBlocking().single()
            api.phoneVerificationsVerify(RestAPIParams.VerificationCode("phone", "code")).toBlocking().single()
            api.phoneVerificationsResend(RestAPIParams.VerificationCodeResend("phone")).toList().toBlocking().single()
            api.postAuthVerifyResend(RestAPIParams.EmptyBody()).toBlocking().single()
            api.logout(RestAPIParams.UserDevices("push-token")).toBlocking().single()

            assertEquals(
                listOf(
                    "/api/v9/auth/login",
                    "/api/v9/auth/register",
                    "/api/v9/auth/mfa/totp",
                    "/api/v9/phone-verifications/verify",
                    "/api/v9/phone-verifications/resend",
                    "/api/v9/auth/verify/resend",
                    "/api/v9/auth/logout",
                ),
                List(7) { server.takeRequest().path },
            )
        } finally {
            server.shutdown()
        }
    }

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
                "getAll" -> values.toMap()
                "getBoolean", "getInt", "getLong", "getFloat", "getString", "getStringSet" -> values[args[0]] ?: args[1]
                "contains" -> values.containsKey(args[0])
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
    }
}
