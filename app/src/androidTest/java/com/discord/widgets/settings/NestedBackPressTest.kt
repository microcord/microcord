package com.discord.widgets.settings

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.app.AppActivity
import com.discord.stores.AuthNavigationState
import com.discord.stores.StoreStream
import com.discord.widgets.channels.WidgetChannelSidebarActions
import com.discord.widgets.chat.pins.WidgetChannelPinnedMessages
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.tabs.NavigationTab
import com.discord.widgets.tabs.WidgetTabsHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    }

    @Test
    fun backFromNestedSettingsReturnsToSettingsTab() {
        val activity = launchLoggedInShell()
        val tabs = tabs(activity)
        StoreStream.getTabsNavigation().selectTab(NavigationTab.SETTINGS, true)
        await { StoreStream.getTabsNavigation().getSelectedTab() == NavigationTab.SETTINGS }

        instrumentation.runOnMainSync {
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

        instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
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
            WidgetChannelSidebarActions.onPins?.invoke(1L)
            home.childFragmentManager.executePendingTransactions()
        }
        assertTrue(
            home.childFragmentManager.findFragmentById(R.id.panel_center) is
                WidgetChannelPinnedMessages,
        )

        instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
        instrumentation.runOnMainSync { home.childFragmentManager.executePendingTransactions() }

        assertFalse(
            home.childFragmentManager.findFragmentById(R.id.panel_center) is
                WidgetChannelPinnedMessages,
        )
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
            instrumentation.runOnMainSync {
                it.supportFragmentManager.executePendingTransactions()
                tabs(it).childFragmentManager.executePendingTransactions()
            }
        }
    }

    private fun tabs(activity: AppActivity) =
        activity.supportFragmentManager.findFragmentById(R.id.app_root) as WidgetTabsHost

    private fun await(condition: () -> Boolean) {
        repeat(100) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("Timed out waiting for navigation", condition())
    }
}
