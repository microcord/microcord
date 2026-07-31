package com.discord.widgets.settings

import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.api.user.User
import com.discord.app.AppActivity
import com.discord.app.AppScreenRouter
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelUserSettings
import com.discord.restapi.RestAPIInterface
import com.discord.stores.AuthNavigationState
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rx.Observable

@RunWith(AndroidJUnit4::class)
class WidgetSettingsPrivacyColdLaunchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val originalApi = RestAPI.apiOrNull
    private var activity: AppActivity? = null
    private var scenario: ActivityScenario<AppActivity>? = null

    @After
    fun cleanUp() {
        scenario?.close()
        originalApi?.let(RestAPI::install) ?: RestAPI.reset()
        StoreStream.getUserSettings().restoreApiForTesting(originalApi)
        StoreStream.getNavigation().updateAuthState(AuthNavigationState(false, false))
    }

    @Test
    fun authenticatedReadyWithEmptyConsentResponseExitsLoading() {
        val api = apiReturningEmptyConsents()
        RestAPI.install(api)
        StoreStream.getUserSettings().bindApi(api)
        StoreStream.getNavigation().updateAuthState(AuthNavigationState(true, true, true))

        scenario = ActivityScenario.launch<AppActivity>(
            AppScreenRouter.createScreenIntent(
                instrumentation.targetContext,
                WidgetSettingsPrivacy::class.java,
            ),
        ).also { launched -> launched.onActivity { activity = it } }

        val fragment = activity!!.supportFragmentManager.findFragmentById(R.id.app_root) as WidgetSettingsPrivacy
        StoreStream.initialize(instrumentation.targetContext).handleConnectionOpen(readyPayload())
        await {
            fragment.requireView().findViewById<View>(R.id.settings_privacy_controls).visibility == View.VISIBLE
        }
        assertEquals(
            View.GONE,
            fragment.requireView().findViewById<ViewGroup>(R.id.dimmer_view).getChildAt(0).visibility,
        )
        assertEquals(
            View.VISIBLE,
            fragment.requireView().findViewById<View>(R.id.settings_privacy_controls).visibility,
        )
    }

    private fun readyPayload() = ModelPayload().apply {
        me = User(id = 42L, username = "privacy-test", verified = false)
        userSettings = ModelUserSettings(
            explicitContentFilter = ModelUserSettings.EXPLICIT_CONTENT_FILTER_NON_FRIENDS,
            defaultGuildsRestricted = true,
            friendSourceFlags = ModelUserSettings.FriendSourceFlags(mutualFriends = true),
            friendDiscoveryFlags = 0,
        )
        guilds = emptyList()
        relationships = emptyList()
        privateChannels = emptyList()
        connectedAccounts = emptyList()
        users = emptyList()
        guildPresences = emptyList()
        guildMembers = emptyList()
        presences = emptyList()
        sessionId = "privacy-test-session"
    }

    private fun apiReturningEmptyConsents() = Proxy.newProxyInstance(
        RestAPIInterface::class.java.classLoader,
        arrayOf(RestAPIInterface::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getConsents" -> Observable.empty<Any>()
            "getHarvestStatus" -> Observable.error<Any>(IllegalStateException("No harvest status"))
            "toString" -> "PrivacyInstrumentationApi"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> throw UnsupportedOperationException(method.name)
        }
    } as RestAPIInterface

    private fun await(condition: () -> Boolean) {
        repeat(200) {
            instrumentation.waitForIdleSync()
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(20)
        }
        assertTrue("Timed out waiting for privacy screen state", condition())
    }

}
