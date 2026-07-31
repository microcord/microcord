package com.discord.widgets.search

import android.content.Intent
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.app.AppActivity
import com.discord.stores.AuthNavigationState
import com.discord.stores.StoreStream
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.search.results.WidgetSearchResults
import com.discord.widgets.search.suggestions.WidgetSearchSuggestions
import com.discord.widgets.tabs.NavigationTab
import com.discord.widgets.tabs.WidgetTabsHost
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchBackPressTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @After
    fun cleanUp() {
        WidgetTabsHost.homeFragmentFactory = null
        StoreStream.getNavigation().updateAuthState(AuthNavigationState(false, false))
        instrumentation.runOnMainSync {
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED, Stage.STARTED)
                .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                .filterIsInstance<AppActivity>()
                .forEach(AppActivity::finish)
        }
    }

    @Test
    fun searchFieldBackReturnsToTheSameHomeScreen() {
        val (homeActivity, home, homeView) = launchSearch()
        val searchActivity = awaitResumedActivity { it !== homeActivity }
        val search = searchActivity.supportFragmentManager.findFragmentById(R.id.app_root) as WidgetSearch

        instrumentation.runOnMainSync {
            search.requireView()
                .findViewById<TextInputLayout>(R.id.widget_search_input)
                .findViewById<android.view.View>(com.google.android.material.R.id.text_input_start_icon)
                .performClick()
        }

        assertSame(homeActivity, awaitResumedActivity { it === homeActivity })
        assertSame(home, home(homeActivity))
        assertSame(homeView, home.requireView())
        assertFalse(homeActivity.isFinishing)
    }

    @Test
    fun physicalBackReturnsToTheSameHomeScreen() {
        val (homeActivity, home, homeView) = launchSearch()
        awaitResumedActivity { it !== homeActivity }

        Espresso.pressBack()
        instrumentation.waitForIdleSync()

        assertSame(homeActivity, awaitResumedActivity { it === homeActivity })
        assertSame(home, home(homeActivity))
        assertSame(homeView, home.requireView())
        assertFalse(homeActivity.isFinishing)
    }

    private fun launchSearch(): Triple<AppActivity.Main, WidgetHome, android.view.View> {
        val channel = Gson().fromJson(
            """{"type":3,"guild_id":0,"name":"Existing chat","id":$CHANNEL_ID}""",
            Channel::class.java,
        )
        StoreStream.getChannels().onGroupCreated(channel)
        await { StoreStream.getChannels().getChannel(CHANNEL_ID) != null }

        WidgetTabsHost.homeFragmentFactory = ::WidgetHome
        StoreStream.getNavigation().updateAuthState(AuthNavigationState(true, true, true))
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, AppActivity.Main::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AppActivity.Main
        await { activity.supportFragmentManager.findFragmentById(R.id.app_root) is WidgetTabsHost }
        lateinit var home: WidgetHome
        lateinit var homeView: android.view.View
        instrumentation.runOnMainSync {
            activity.onStateNotSaved()
            activity.supportFragmentManager.executePendingTransactions()
            tabs(activity).childFragmentManager.executePendingTransactions()
            home = home(activity)
            homeView = home.requireView()
            WidgetSearch.launchForChannel(CHANNEL_ID, activity)
        }
        val searchActivity = awaitResumedActivity { it !== activity }
        instrumentation.runOnMainSync {
            searchActivity.supportFragmentManager.executePendingTransactions()
            val search = searchActivity.supportFragmentManager.findFragmentById(R.id.app_root) as WidgetSearch
            search.childFragmentManager.executePendingTransactions()
            assertTrue(
                search.childFragmentManager.findFragmentById(R.id.widget_search_suggestions) is
                    WidgetSearchSuggestions,
            )
            assertTrue(
                search.childFragmentManager.findFragmentById(R.id.widget_search_results) is
                    WidgetSearchResults,
            )
            assertTrue(searchActivity.supportFragmentManager.backStackEntryCount == 0)
            assertTrue(search.childFragmentManager.backStackEntryCount == 0)
            assertFalse(searchActivity.backPressHandlers.isEmpty)
        }
        return Triple(activity, home, homeView)
    }

    private fun tabs(activity: AppActivity) =
        activity.supportFragmentManager.findFragmentById(R.id.app_root) as WidgetTabsHost

    private fun home(activity: AppActivity) =
        tabs(activity).childFragmentManager.findFragmentByTag(NavigationTab.HOME.name) as WidgetHome

    private fun awaitResumedActivity(predicate: (AppActivity) -> Boolean): AppActivity {
        repeat(250) {
            instrumentation.waitForIdleSync()
            var match: AppActivity? = null
            instrumentation.runOnMainSync {
                match = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<AppActivity>()
                    .firstOrNull(predicate)
            }
            if (match != null) return requireNotNull(match)
            Thread.sleep(20)
        }
        error("Timed out waiting for resumed AppActivity")
    }

    private fun await(condition: () -> Boolean) {
        repeat(100) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("Timed out waiting for navigation", condition())
    }

    private companion object {
        const val CHANNEL_ID = 987654321L
    }
}
