package com.discord.widgets.home

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.app.AppActivity
import com.discord.panels.OverlappingPanelsLayout
import com.discord.stores.AuthNavigationState
import com.discord.stores.StoreStream
import com.discord.widgets.tabs.NavigationTab
import com.discord.widgets.tabs.WidgetTabsHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Back navigation on the logged-in shell.
 *
 * These pin the behaviour that regressed: a back press from the chat surface has to step back to
 * the guild/channel list, and a back press on a secondary tab has to return to the home tab.
 * Neither may leave the activity.
 */
@RunWith(AndroidJUnit4::class)
class HomeBackPressTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @After
    fun cleanUp() {
        WidgetTabsHost.homeFragmentFactory = null
        StoreStream.getNavigation().updateAuthState(AuthNavigationState(false, false))
    }

    @Test
    fun backFromTheChatPanelOpensTheLeftPanelInsteadOfLeavingTheApp() {
        val activity = launchLoggedInShell()
        val panels = homePanels(activity)

        // The chat surface: neither side panel resting in front of the user.
        instrumentation.runOnMainSync {
            panels.setStartPanelLockState(OverlappingPanelsLayout.LockState.UNLOCKED)
            panels.closePanels()
        }
        awaitPanel(panels, OverlappingPanelsLayout.Panel.CENTER)

        instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }

        awaitPanel(panels, OverlappingPanelsLayout.Panel.START)
        assertFalse("Back from the chat panel must not finish the activity", activity.isFinishing)
        instrumentation.runOnMainSync(activity::finish)
    }

    @Test
    fun backOnTheOpenLeftPanelLeavesTheAppWithoutFinishingTheActivity() {
        val activity = launchLoggedInShell()
        val panels = homePanels(activity)

        instrumentation.runOnMainSync {
            panels.setStartPanelLockState(OverlappingPanelsLayout.LockState.UNLOCKED)
            panels.openStartPanel()
        }
        awaitPanel(panels, OverlappingPanelsLayout.Panel.START)

        instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
        instrumentation.waitForIdleSync()

        // The reference moves the task to the background rather than destroying it, so the
        // activity survives and the panels stay where they were.
        assertFalse(activity.isFinishing)
        assertEquals(OverlappingPanelsLayout.Panel.START, panels.getSelectedPanel())
        instrumentation.runOnMainSync(activity::finish)
    }

    @Test
    fun backOnASecondaryTabReturnsToTheHomeTab() {
        val activity = launchLoggedInShell()
        val tabsNavigation = StoreStream.getTabsNavigation()

        tabsNavigation.selectTab(NavigationTab.MENTIONS, true)
        awaitTab(NavigationTab.MENTIONS)

        instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }

        awaitTab(NavigationTab.HOME)
        assertFalse("Back from a secondary tab must not finish the activity", activity.isFinishing)
        instrumentation.runOnMainSync(activity::finish)
    }

    private fun launchLoggedInShell(): AppActivity.Main {
        WidgetTabsHost.homeFragmentFactory = ::WidgetHome
        StoreStream.getNavigation().updateAuthState(AuthNavigationState(true, true, true))
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, AppActivity.Main::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AppActivity.Main
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { activity.supportFragmentManager.executePendingTransactions() }
        return activity
    }

    private fun homePanels(activity: AppActivity): HomePanelsLayout {
        val tabs = activity.supportFragmentManager.findFragmentById(R.id.app_root) as WidgetTabsHost
        instrumentation.runOnMainSync { tabs.childFragmentManager.executePendingTransactions() }
        val home = tabs.childFragmentManager.findFragmentByTag(NavigationTab.HOME.name) as WidgetHome
        return home.requireView().findViewById(R.id.overlapping_panels)
    }

    private fun awaitPanel(panels: HomePanelsLayout, expected: OverlappingPanelsLayout.Panel) {
        await { panels.getSelectedPanel() == expected }
        assertEquals(expected, panels.getSelectedPanel())
    }

    private fun awaitTab(expected: NavigationTab) {
        await { StoreStream.getTabsNavigation().getSelectedTab() == expected }
        assertEquals(expected, StoreStream.getTabsNavigation().getSelectedTab())
    }

    /** Panel moves are animated and tab selection round-trips through the store thread. */
    private fun await(condition: () -> Boolean) {
        repeat(100) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("Timed out waiting for the expected shell state", condition())
    }
}
