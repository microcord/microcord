package com.discord.widgets.settings

import android.content.Intent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.user.User
import com.discord.app.AppActivity
import com.discord.app.AppScreenRouter
import com.discord.panels.OverlappingPanelsLayout
import com.discord.models.domain.ModelPayload
import com.discord.stores.AuthNavigationState
import com.discord.stores.StoreStream
import com.discord.widgets.chat.pins.WidgetChannelPinnedMessages
import com.discord.widgets.home.HomePanelsLayout
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.tabs.NavigationTab
import com.discord.widgets.tabs.WidgetTabsHost
import com.discord.widgets.settings.account.WidgetSettingsAccount
import com.discord.widgets.settings.account.WidgetSettingsBlockedUsers
import com.google.gson.Gson
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
    fun blockedUsersIsOnlyReachableFromMyAccountAndBackUnwindsToSettings() {
        val activity = launchLoggedInShell()
        val tabs = tabs(activity)
        StoreStream.getTabsNavigation().selectTab(NavigationTab.SETTINGS, true)
        await { StoreStream.getTabsNavigation().getSelectedTab() == NavigationTab.SETTINGS }

        instrumentation.runOnMainSync {
            activity.onStateNotSaved()
            tabs.childFragmentManager.executePendingTransactions()
            val settings = tabs.childFragmentManager
                .findFragmentById(R.id.widget_tabs_host_user_settings) as WidgetSettings
            assertEquals(View.GONE, settings.requireView().findViewById<View>(R.id.blocked_users).visibility)
            settings.requireView().findViewById<View>(R.id.account).performClick()
            tabs.childFragmentManager.executePendingTransactions()
        }
        val account = tabs.childFragmentManager
            .findFragmentById(R.id.widget_tabs_host_user_settings) as WidgetSettingsAccount
        await { account.requireView().findViewById<View>(R.id.settings_blocked_users).hasOnClickListeners() }

        instrumentation.runOnMainSync {
            account.requireView().findViewById<View>(R.id.settings_blocked_users).performClick()
        }
        val blockedUsersActivity = awaitResumedActivity { it !== activity }
        assertTrue(
            blockedUsersActivity.supportFragmentManager.findFragmentById(R.id.app_root) is
                WidgetSettingsBlockedUsers,
        )

        pressPhoneBack()
        assertSame(activity, awaitResumedActivity { it === activity })
        assertTrue(
            tabs.childFragmentManager.findFragmentById(R.id.widget_tabs_host_user_settings) is
                WidgetSettingsAccount,
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
        seedChannel(1L)
        val activity = launchLoggedInShell()
        val tabs = tabs(activity)
        val home = tabs.childFragmentManager.findFragmentByTag(NavigationTab.HOME.name) as WidgetHome
        val homeView = home.requireView()
        val panels = home.getPanelLayout() as HomePanelsLayout

        instrumentation.runOnMainSync {
            panels.setStartPanelLockState(OverlappingPanelsLayout.LockState.UNLOCKED)
            panels.openStartPanel()
        }
        await { panels.getSelectedPanel() == OverlappingPanelsLayout.Panel.START }

        instrumentation.runOnMainSync {
            WidgetChannelPinnedMessages.show(activity, 1L)
        }
        val pinsActivity = awaitResumedActivity { it !== activity }
        instrumentation.runOnMainSync {
            pinsActivity.supportFragmentManager.executePendingTransactions()
        }
        val pinned = pinsActivity.supportFragmentManager.findFragmentById(R.id.app_root) as WidgetChannelPinnedMessages
        assertSame(pinned.requireView().findViewById<Toolbar>(R.id.action_bar_toolbar), pinsActivity.toolbar)
        assertEquals(pinsActivity.getString(R.string.pinned_messages), pinsActivity.toolbarTitleLayout?.title?.text)
        assertEquals(1L, pinsActivity.mostRecentIntent.getLongExtra("INTENT_EXTRA_CHANNEL_ID", 0L))
        assertTrue(pinsActivity.mostRecentIntent.hasExtra(AppScreenRouter.EXTRA_TRANSITION))
        assertEquals(WidgetChannelPinnedMessages.TRANSITION.animations, pinsActivity.transitionAnimations)
        assertSame(homeView, home.requireView())
        assertEquals(OverlappingPanelsLayout.Panel.START, panels.getSelectedPanel())

        instrumentation.runOnMainSync { AppScreenRouter.recreate(pinsActivity) }
        val restoredPinsActivity = awaitResumedActivity { it !== activity && it !== pinsActivity }
        instrumentation.runOnMainSync {
            restoredPinsActivity.supportFragmentManager.executePendingTransactions()
        }
        assertTrue(
            restoredPinsActivity.supportFragmentManager.findFragmentById(R.id.app_root) is
                WidgetChannelPinnedMessages,
        )
        assertTrue(restoredPinsActivity.mostRecentIntent.hasExtra(AppScreenRouter.EXTRA_TRANSITION))
        assertEquals(
            WidgetChannelPinnedMessages.TRANSITION.animations,
            restoredPinsActivity.transitionAnimations,
        )

        pressPhoneBack()
        assertSame(activity, awaitResumedActivity { it === activity })
        assertSame(home, tabs(activity).childFragmentManager.findFragmentByTag(NavigationTab.HOME.name))
        assertSame(homeView, home.requireView())
        assertEquals(OverlappingPanelsLayout.Panel.START, panels.getSelectedPanel())
        assertFalse(activity.isFinishing)
        instrumentation.runOnMainSync(activity::finish)
    }

    @Test
    fun backFromFullScreenSettingsPreservesHomeViewAndPanel() {
        val activity = launchLoggedInShell()
        val home = tabs(activity).childFragmentManager
            .findFragmentByTag(NavigationTab.HOME.name) as WidgetHome
        val homeView = home.requireView()
        val panels = home.getPanelLayout() as HomePanelsLayout

        instrumentation.runOnMainSync {
            activity.onStateNotSaved()
            panels.openStartPanel()
        }
        await { panels.getSelectedPanel() == OverlappingPanelsLayout.Panel.START }
        instrumentation.runOnMainSync {
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
        seedUser()
        WidgetTabsHost.homeFragmentFactory = ::WidgetHome
        StoreStream.getNavigation().updateAuthState(AuthNavigationState(true, true))
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

    private fun seedUser() {
        val user = User(1L, "navigation-test", verified = true)
        val payload = ModelPayload().also {
            ModelPayload::class.java.getDeclaredField("me").apply { isAccessible = true }.set(it, user)
        }
        val stream = StoreStream::class.java.getDeclaredField("instance").run {
            isAccessible = true
            get(null) as StoreStream
        }
        stream.dispatcher.schedule { StoreStream.getUsers().handleConnectionOpen(payload) }
        await { StoreStream.getUsers().getMe().id == user.id }
    }

    private fun seedChannel(channelId: Long) {
        StoreStream.getChannels().onGroupCreated(
            Gson().fromJson(
                """{"type":3,"guild_id":0,"name":"Pinned test","id":$channelId}""",
                Channel::class.java,
            ),
        )
        StoreStream.selectChannel(0L, channelId)
        await { StoreStream.getChannels().getChannel(channelId) != null }
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
