package com.discord.widgets.user

import android.content.Intent
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.discord.R
import com.discord.app.AppActivity
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.stores.AuthNavigationState
import com.discord.stores.StoreStream
import com.discord.widgets.friends.WidgetFriendsList
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.tabs.NavigationTab
import com.discord.widgets.tabs.WidgetTabsHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rx.Observable

@RunWith(AndroidJUnit4::class)
class MutualNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var originalFriendsSource: ((Long) -> Observable<WidgetUserMutualFriends.State>)? = null
    private var originalGuildsSource: ((Long) -> Observable<WidgetUserMutualGuilds.State>)? = null

    @Before
    fun saveSources() {
        originalFriendsSource = WidgetUserMutualFriends.stateSource
        originalGuildsSource = WidgetUserMutualGuilds.stateSource
    }

    @After
    fun cleanUp() {
        WidgetUserMutualFriends.stateSource = originalFriendsSource
        WidgetUserMutualGuilds.stateSource = originalGuildsSource
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
    fun mutualFriendsIsFullScreenAndBackPreservesFriendsTab() {
        var requestedUserId = 0L
        WidgetUserMutualFriends.stateSource = { userId ->
            requestedUserId = userId
            Observable.just(WidgetUserMutualFriends.State.Loaded(USERNAME, emptyList()))
        }
        val activity = launchLoggedInShell(NavigationTab.FRIENDS)
        val tabs = tabs(activity)
        val friends = tabs.childFragmentManager.findFragmentByTag(NavigationTab.FRIENDS.name) as WidgetFriendsList
        val friendsView = friends.requireView()
        val friendsRecycler = friendsView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.friends_list_recycler)
        val layoutManager = friendsRecycler.layoutManager

        instrumentation.runOnMainSync { WidgetUserMutualFriends.show(activity, user()) }
        val destinationActivity = awaitResumedActivity { it !== activity }
        val destination = destination(destinationActivity) as WidgetUserMutualFriends
        await {
            requestedUserId == USER_ID &&
                destinationActivity.toolbarTitleLayout
                    ?.findViewById<android.widget.TextView>(R.id.toolbar_title_subtext)?.text == USERNAME
        }

        assertEquals(
            destination.resources.getQuantityString(R.plurals.user_profile_mutual_friends_count, 0, 0),
            destinationActivity.toolbarTitleLayout?.title?.text,
        )
        assertEquals(0, destinationActivity.supportFragmentManager.backStackEntryCount)

        Espresso.pressBack()
        assertSame(activity, awaitResumedActivity { it === activity })
        assertSame(friends, tabs(activity).childFragmentManager.findFragmentByTag(NavigationTab.FRIENDS.name))
        assertSame(friendsView, friends.requireView())
        assertSame(layoutManager, friendsRecycler.layoutManager)
        assertEquals(NavigationTab.FRIENDS, StoreStream.getTabsNavigation().getSelectedTab())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun mutualGuildsIsFullScreenAndToolbarBackPreservesHome() {
        var requestedUserId = 0L
        WidgetUserMutualGuilds.stateSource = { userId ->
            requestedUserId = userId
            Observable.just(WidgetUserMutualGuilds.State.Loaded(USERNAME, emptyList()))
        }
        val activity = launchLoggedInShell(NavigationTab.HOME)
        val home = tabs(activity).childFragmentManager.findFragmentByTag(NavigationTab.HOME.name) as WidgetHome
        val homeView = home.requireView()

        instrumentation.runOnMainSync { WidgetUserMutualGuilds.show(activity, USER_ID) }
        val destinationActivity = awaitResumedActivity { it !== activity }
        val destination = destination(destinationActivity) as WidgetUserMutualGuilds
        await {
            requestedUserId == USER_ID &&
                destinationActivity.toolbarTitleLayout
                    ?.findViewById<android.widget.TextView>(R.id.toolbar_title_subtext)?.text == USERNAME
        }

        assertEquals(
            destination.resources.getQuantityString(R.plurals.user_profile_mutual_guilds_count, 0, 0),
            destinationActivity.toolbarTitleLayout?.title?.text,
        )
        assertEquals(0, destinationActivity.supportFragmentManager.backStackEntryCount)

        instrumentation.runOnMainSync {
            val toolbar = requireNotNull(destinationActivity.toolbar)
            (0 until toolbar.childCount)
                .map(toolbar::getChildAt)
                .first { it is android.widget.ImageButton }
                .performClick()
        }
        assertSame(activity, awaitResumedActivity { it === activity })
        assertSame(home, tabs(activity).childFragmentManager.findFragmentByTag(NavigationTab.HOME.name))
        assertSame(homeView, home.requireView())
        assertEquals(NavigationTab.HOME, StoreStream.getTabsNavigation().getSelectedTab())
        assertFalse(activity.isFinishing)
    }

    private fun launchLoggedInShell(tab: NavigationTab): AppActivity.Main {
        WidgetTabsHost.homeFragmentFactory = ::WidgetHome
        StoreStream.getNavigation().updateAuthState(AuthNavigationState(true, true, true))
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, AppActivity.Main::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AppActivity.Main
        await { activity.supportFragmentManager.findFragmentById(R.id.app_root) is WidgetTabsHost }
        StoreStream.getTabsNavigation().selectTab(tab, true)
        await { StoreStream.getTabsNavigation().getSelectedTab() == tab }
        instrumentation.runOnMainSync {
            activity.onStateNotSaved()
            activity.supportFragmentManager.executePendingTransactions()
            tabs(activity).childFragmentManager.executePendingTransactions()
        }
        return activity
    }

    private fun tabs(activity: AppActivity) =
        activity.supportFragmentManager.findFragmentById(R.id.app_root) as WidgetTabsHost

    private fun destination(activity: AppActivity): androidx.fragment.app.Fragment {
        instrumentation.runOnMainSync { activity.supportFragmentManager.executePendingTransactions() }
        return requireNotNull(activity.supportFragmentManager.findFragmentById(R.id.app_root))
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

    private fun user(): User = CoreUser(id = USER_ID, username = USERNAME, discriminator = 1)

    private companion object {
        const val USER_ID = 42L
        const val USERNAME = "source-user"
    }
}
