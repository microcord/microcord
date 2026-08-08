package com.discord.widgets.home

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.app.AppActivity
import com.discord.panels.OverlappingPanelsLayout
import com.discord.stores.AuthNavigationState
import com.discord.stores.StoreStream
import com.discord.widgets.chat.input.AppFlexInputViewModel
import com.discord.widgets.tabs.NavigationTab
import com.discord.widgets.tabs.WidgetTabsHost
import com.discord.utilities.toolbar.getNavigationIconView
import com.google.gson.Gson
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
    fun toolbarNavigationButtonOpensTheChannelList() {
        val activity = launchLoggedInShell()
        StoreStream.getChannels().onGroupCreated(
            Gson().fromJson(
                """{"type":3,"guild_id":0,"name":"Toolbar test","id":$CHANNEL_ID}""",
                Channel::class.java,
            ),
        )
        await { StoreStream.getChannels().getChannel(CHANNEL_ID) != null }
        StoreStream.getChannels().javaClass.getDeclaredField("initializedForAuthedUser").run {
            isAccessible = true
            setBoolean(StoreStream.getChannels(), true)
        }
        StoreStream.selectChannel(0L, CHANNEL_ID)
        await { StoreStream.getChannelsSelected().getId() == CHANNEL_ID }
        val home = homeFragment(activity)
        val panels = home.requireView().findViewById<HomePanelsLayout>(R.id.overlapping_panels)
        await { home.requireView().findViewById<android.view.View>(R.id.action_bar_toolbar_layout) != null }
        instrumentation.runOnMainSync {
            panels.setStartPanelLockState(OverlappingPanelsLayout.LockState.UNLOCKED)
            panels.closePanels()
        }
        awaitPanel(panels, OverlappingPanelsLayout.Panel.CENTER)
        val toolbar = home.requireView()
            .findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.action_bar_toolbar_layout)
            .getChildAt(0) as Toolbar

        instrumentation.runOnMainSync { requireNotNull(toolbar.getNavigationIconView()).performClick() }

        awaitPanel(panels, OverlappingPanelsLayout.Panel.START)
        instrumentation.runOnMainSync(activity::finish)
    }

    @Test
    fun openingEitherSidePanelDismissesInputWithoutReopeningItOnClose() {
        val activity = launchLoggedInShell()
        val panels = homePanels(activity)
        lateinit var flexInput: AppFlexInputViewModel
        val keyboardEvents = mutableListOf<AppFlexInputViewModel.Event>()
        instrumentation.runOnMainSync {
            flexInput = ViewModelProvider(activity).get(AppFlexInputViewModel::class.java)
        }
        val subscription = flexInput.observeEvents().subscribe(keyboardEvents::add)

        instrumentation.runOnMainSync {
            panels.setStartPanelLockState(OverlappingPanelsLayout.LockState.UNLOCKED)
            panels.setEndPanelLockState(OverlappingPanelsLayout.LockState.UNLOCKED)
            panels.closePanels()
        }
        awaitPanel(panels, OverlappingPanelsLayout.Panel.CENTER)

        instrumentation.runOnMainSync {
            flexInput.showExpressionTray()
            keyboardEvents.clear()
            panels.openStartPanel()
        }
        awaitPanel(panels, OverlappingPanelsLayout.Panel.START)
        assertFalse(flexInput.requireViewState().expressionTrayShown)

        instrumentation.runOnMainSync { panels.closePanels() }
        awaitPanel(panels, OverlappingPanelsLayout.Panel.CENTER)
        assertFalse(keyboardEvents.contains(AppFlexInputViewModel.Event.ShowKeyboard))

        instrumentation.runOnMainSync {
            flexInput.showExpressionTray()
            keyboardEvents.clear()
            panels.openEndPanel()
        }
        awaitPanel(panels, OverlappingPanelsLayout.Panel.END)
        assertFalse(flexInput.requireViewState().expressionTrayShown)
        assertFalse(keyboardEvents.contains(AppFlexInputViewModel.Event.ShowKeyboard))

        subscription.unsubscribe()
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
        StoreStream.getNavigation().updateAuthState(AuthNavigationState(true, true))
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, AppActivity.Main::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AppActivity.Main
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { activity.supportFragmentManager.executePendingTransactions() }
        return activity
    }

    private fun homePanels(activity: AppActivity): HomePanelsLayout {
        return homeFragment(activity).requireView().findViewById(R.id.overlapping_panels)
    }

    private fun homeFragment(activity: AppActivity): WidgetHome {
        val tabs = activity.supportFragmentManager.findFragmentById(R.id.app_root) as WidgetTabsHost
        instrumentation.runOnMainSync { tabs.childFragmentManager.executePendingTransactions() }
        return tabs.childFragmentManager.findFragmentByTag(NavigationTab.HOME.name) as WidgetHome
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

    private companion object {
        const val CHANNEL_ID = 987654322L
    }
}
