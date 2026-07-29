package com.discord.widgets.settings

import android.content.Intent
import android.view.View
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.discord.R
import com.discord.app.AppActivity
import com.discord.stores.AuthNavigationState
import com.discord.stores.StoreStream
import com.discord.widgets.channels.WidgetChannelSidebarActions
import com.discord.widgets.chat.pins.WidgetChannelPinnedMessages
import com.discord.widgets.home.HomePanelsLayout
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.tabs.NavigationTab
import com.discord.widgets.tabs.WidgetTabsHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NestedBackPressTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @After
    fun cleanUp() {
        WidgetChannelSidebarActions.onPins = null
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
    fun backFromNestedSettingsReturnsToSettingsTab() {
        val activity = launchLoggedInShell()
        val tabs = tabs(activity)
        StoreStream.getTabsNavigation().selectTab(NavigationTab.SETTINGS, true)
        await { StoreStream.getTabsNavigation().getSelectedTab() == NavigationTab.SETTINGS }

        instrumentation.runOnMainSync {
            activity.onStateNotSaved()
            tabs.childFragmentManager.executePendingTransactions()
            tabs.childFragmentManager.findFragmentById(R.id.widget_tabs_host_user_settings)
                ?.requireView()
                ?.findViewById<android.view.View>(R.id.appearance)
                ?.performClick()
            tabs.childFragmentManager.executePendingTransactions()
        }
        assertTrue(
            tabs.childFragmentManager.findFragmentById(R.id.widget_tabs_host_user_settings) is
                WidgetSettingsAppearance,
        )

        pressPhoneBack()
        instrumentation.runOnMainSync { tabs.childFragmentManager.executePendingTransactions() }

        assertTrue(
            tabs.childFragmentManager.findFragmentById(R.id.widget_tabs_host_user_settings) is
                WidgetSettings,
        )
        assertEquals(NavigationTab.SETTINGS, StoreStream.getTabsNavigation().getSelectedTab())
        assertFalse(activity.isFinishing)
        instrumentation.runOnMainSync(activity::finish)
    }

    @Test
    fun backFromPinnedMessagesReturnsToChat() {
        val activity = launchLoggedInShell()
        val tabs = tabs(activity)
        val home = tabs.childFragmentManager.findFragmentByTag(NavigationTab.HOME.name) as WidgetHome

        instrumentation.runOnMainSync {
            activity.onStateNotSaved()
            WidgetChannelSidebarActions.onPins?.invoke(1L)
            home.childFragmentManager.executePendingTransactions()
        }
        assertTrue(
            home.childFragmentManager.findFragmentById(R.id.widget_home_panel_center_overlay) is
                WidgetChannelPinnedMessages,
        )

        pressPhoneBack()
        instrumentation.runOnMainSync { home.childFragmentManager.executePendingTransactions() }

        assertFalse(
            home.childFragmentManager.findFragmentById(R.id.widget_home_panel_center_overlay) is
                WidgetChannelPinnedMessages,
        )
        assertFalse(activity.isFinishing)
        instrumentation.runOnMainSync(activity::finish)
    }

    @Test
    fun backFromFullScreenSettingsPreservesHomeViewAndPanel() {
        val activity = launchLoggedInShell()
        val home = tabs(activity).childFragmentManager
            .findFragmentByTag(NavigationTab.HOME.name) as WidgetHome
        val homeView = home.requireView()

        instrumentation.runOnMainSync {
            activity.onStateNotSaved()
            home.getPanelLayout().openStartPanel()
            SettingsNavigation.launch(activity, WidgetSettingsAppearance::class.java)
        }
        awaitResumedActivity { it !== activity }

        pressPhoneBack()
        awaitResumedActivity { it === activity }

        val restoredHome = tabs(activity).childFragmentManager
            .findFragmentByTag(NavigationTab.HOME.name) as WidgetHome
        assertSame(home, restoredHome)
        assertSame(homeView, restoredHome.requireView())
        assertEquals(
            com.discord.panels.OverlappingPanelsLayout.Panel.START,
            (restoredHome.getPanelLayout() as HomePanelsLayout).getSelectedPanel(),
        )
        assertFalse(activity.isFinishing)
        instrumentation.runOnMainSync(activity::finish)
    }

    @Test
    fun backFromNestedFullScreenSettingsPopsOneLevelAtATime() {
        val activity = launchLoggedInShell()

        instrumentation.runOnMainSync {
            activity.onStateNotSaved()
            SettingsNavigation.launch(activity, WidgetSettingsAppearance::class.java)
        }
        val serverActivity = awaitResumedActivity { it !== activity }
        instrumentation.runOnMainSync {
            SettingsNavigation.launch(serverActivity, WidgetSettingsLanguage::class.java)
        }
        val overviewActivity = awaitResumedActivity { it !== activity && it !== serverActivity }
        assertTrue(overviewActivity.supportFragmentManager.findFragmentById(R.id.app_root) is WidgetSettingsLanguage)

        pressPhoneBack()
        assertSame(serverActivity, awaitResumedActivity { it === serverActivity })
        assertTrue(serverActivity.supportFragmentManager.findFragmentById(R.id.app_root) is WidgetSettingsAppearance)
        assertFalse(serverActivity.isFinishing)

        pressPhoneBack()
        assertSame(activity, awaitResumedActivity { it === activity })
        assertTrue(activity.supportFragmentManager.findFragmentById(R.id.app_root) is WidgetTabsHost)
        assertFalse(activity.isFinishing)
        instrumentation.runOnMainSync(activity::finish)
    }

    private fun launchLoggedInShell(): AppActivity.Main {
        WidgetTabsHost.homeFragmentFactory = ::WidgetHome
        StoreStream.getNavigation().updateAuthState(AuthNavigationState(true, true, true))
        return (instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, AppActivity.Main::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AppActivity.Main).also {
            instrumentation.waitForIdleSync()
            await { it.supportFragmentManager.findFragmentById(R.id.app_root) is WidgetTabsHost }
            instrumentation.runOnMainSync {
                it.onStateNotSaved()
                it.supportFragmentManager.executePendingTransactions()
                tabs(it).childFragmentManager.executePendingTransactions()
            }
        }
    }

    private fun tabs(activity: AppActivity) =
        activity.supportFragmentManager.findFragmentById(R.id.app_root) as WidgetTabsHost

    private fun pressPhoneBack() {
        Espresso.pressBack()
        instrumentation.waitForIdleSync()
    }

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

}
