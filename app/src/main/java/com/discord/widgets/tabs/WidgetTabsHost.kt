package com.discord.widgets.tabs

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import androidx.fragment.app.commit
import com.discord.R
import com.discord.panels.PanelState
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.intent.AppIntentContract
import com.discord.utilities.intent.DeepLinkRoute
import com.discord.utilities.intent.LinkParseResult
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.friends.WidgetFriendsList
import com.discord.widgets.friends.WidgetFriendsAdd
import com.discord.widgets.friends.WidgetFriendsAddById
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.user.WidgetUserStatusSheet
import com.discord.widgets.user.WidgetUserSetCustomStatus
import com.discord.widgets.channels.memberlist.WidgetChannelMembersList
import com.discord.widgets.directories.WidgetDirectoriesSearch
import com.discord.widgets.user.WidgetUserMutualFriends
import com.discord.widgets.user.WidgetUserMutualGuilds
import com.discord.widgets.captcha.WidgetCaptchaBottomSheet
import com.discord.widgets.settings.WidgetSettings
import com.discord.widgets.settings.account.WidgetSettingsBlockedUsers
import com.discord.widgets.user.WidgetUserMentions
import com.discord.widgets.user.search.WidgetGlobalSearch
import com.discord.widgets.user.search.WidgetGlobalSearchDialog
import rx.Subscription

internal fun shouldShowBottomNavigation(tab: NavigationTab, leftPanelState: PanelState) =
    tab != NavigationTab.HOME || leftPanelState == PanelState.Open || leftPanelState == PanelState.Opening

internal val mainNavigationTabs = setOf(
    NavigationTab.HOME,
    NavigationTab.FRIENDS,
    NavigationTab.SEARCH,
    NavigationTab.MENTIONS,
    NavigationTab.SETTINGS,
)

class WidgetTabsHost : AppFragment(R.layout.widget_tabs_host) {
    private lateinit var bottomNavigation: TabsHostBottomNavigationView
    private var selectedTabSubscription: Subscription? = null
    private var pendingIntentSubscription: Subscription? = null
    private var leftPanelSubscription: Subscription? = null
    private var profileSubscription: Subscription? = null
    private var restoredTab: NavigationTab? = null
    private var leftPanelState: PanelState = PanelState.Closed
    private val destinations = mapOf(
        NavigationTab.FRIENDS to Destination(R.id.widget_tabs_host_friends, "com.discord.widgets.friends.WidgetFriendsList"),
        NavigationTab.SEARCH to Destination(R.id.widget_tabs_host_search, "com.discord.widgets.user.search.WidgetGlobalSearch"),
        NavigationTab.MENTIONS to Destination(R.id.widget_tabs_host_mentions, "com.discord.widgets.user.WidgetUserMentions"),
        NavigationTab.SETTINGS to Destination(R.id.widget_tabs_host_user_settings, "com.discord.widgets.settings.WidgetSettings"),
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bottomNavigation = view.findViewById(R.id.widget_tabs_host_bottom_navigation_view)
        // Registered before the home fragment is committed below so the tabs host keeps the first
        // look at every back press; see BackPressHandlerRegistry for why that order matters.
        setOnBackPressed(::handleBackPress)
        WidgetFriendsList.onAddFriend = {
            childFragmentManager.commit {
                replace(R.id.widget_tabs_host_friends, WidgetFriendsAdd())
                addToBackStack("add_friend")
            }
        }
        WidgetFriendsList.onProfile = { WidgetUserSheet.show(it.id, childFragmentManager) }
        WidgetFriendsAddById.onCaptcha = { siteKey, rqtoken, done ->
            WidgetCaptchaBottomSheet.show(
                childFragmentManager, "friend:${System.nanoTime()}", siteKey, rqtoken,
            ) { token, _ -> done(token) }
        }
        WidgetChannelMembersList.onUserClicked = { WidgetUserSheet.show(it, childFragmentManager) }
        WidgetUserStatusSheet.onCustomStatus = {
            childFragmentManager.commit {
                replace(activeTabContainerId(), WidgetUserSetCustomStatus())
                addToBackStack("custom_status")
            }
        }
        WidgetUserSheet.onMutualFriends = { userId ->
            childFragmentManager.commit {
                replace(activeTabContainerId(), WidgetUserMutualFriends.newInstance(userId))
                addToBackStack("mutual_friends")
            }
        }
        WidgetUserSheet.onMutualGuilds = { userId ->
            childFragmentManager.commit {
                replace(activeTabContainerId(), WidgetUserMutualGuilds.newInstance(userId))
                addToBackStack("mutual_guilds")
            }
        }
        WidgetUserMutualFriends.onFriendSelected = { WidgetUserSheet.show(it, childFragmentManager) }
        WidgetUserMutualGuilds.onGuildSelected = {
            childFragmentManager.popBackStack()
            StoreStream.getTabsNavigation().selectTab(NavigationTab.HOME, true)
            StoreStream.selectGuild(it)
        }
        WidgetSettings.onSetStatus = { WidgetUserStatusSheet.show(childFragmentManager) }
        WidgetSettings.onBlockedUsers = {
            childFragmentManager.commit {
                replace(R.id.widget_tabs_host_user_settings, WidgetSettingsBlockedUsers())
                addToBackStack("blocked_users")
            }
        }
        WidgetSettings.onLogout = { StoreStream.getAuthentication().logout() }
        WidgetSettingsBlockedUsers.onProfile = { WidgetUserSheet.show(it.id, childFragmentManager) }
        if (childFragmentManager.findFragmentByTag(NavigationTab.HOME.name) == null) {
            childFragmentManager.commit {
                replace(R.id.widget_tabs_host_home, homeFragmentFactory?.invoke() ?: WidgetHome(), NavigationTab.HOME.name)
            }
        }
        restoredTab = savedInstanceState?.getString(STATE_SELECTED_TAB)?.let {
            runCatching { NavigationTab.valueOf(it) }.getOrNull()
        }
        restoredTab?.let {
            showTab(it)
            StoreStream.getTabsNavigation().selectTab(it)
        }
    }

    override fun onStart() {
        super.onStart()
        selectedTabSubscription = StoreStream.getTabsNavigation().observeSelectedTab().subscribe { tab ->
            activity?.runOnUiThread {
                if (restoredTab == null || restoredTab == tab) {
                    restoredTab = null
                    showTab(tab)
                }
            }
        }
        pendingIntentSubscription = StoreStream.getNavigation().observePendingIntent().subscribe { contract ->
            activity?.runOnUiThread { consumeOwnedRoute(contract) }
        }
        leftPanelSubscription = StoreStream.getNavigation().observeLeftPanelState().subscribe { state ->
            leftPanelState = state
            activity?.runOnUiThread { updateBottomNavigationVisibility() }
        }
        profileSubscription = rx.Observable.combineLatest(
            StoreStream.getUsers().observeMe(),
            StoreStream.getPresences().observeAllPresences(),
        ) { user, presences -> user to presences[user.id] }.subscribe {
            activity?.runOnUiThread { updateBottomNavigation() }
        }
    }

    override fun onStop() {
        selectedTabSubscription?.unsubscribe()
        selectedTabSubscription = null
        pendingIntentSubscription?.unsubscribe()
        pendingIntentSubscription = null
        leftPanelSubscription?.unsubscribe()
        leftPanelSubscription = null
        profileSubscription?.unsubscribe()
        profileSubscription = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_TAB, StoreStream.getTabsNavigation().getSelectedTab().name)
        super.onSaveInstanceState(outState)
    }

    private fun consumeOwnedRoute(contract: AppIntentContract) {
        val route = ((contract as? AppIntentContract.Link)?.result as? LinkParseResult.Internal)?.route ?: return
        if (route is DeepLinkRoute.User) {
            WidgetUserSheet.show(route.userId, childFragmentManager)
            StoreStream.getNavigation().consumePendingIntent(contract)
            return
        }
        // A `/query/` App Action names a destination rather than identifying one; the quick switcher
        // is what resolves a name, so the link opens it pre-filled.
        if (route is DeepLinkRoute.Query) {
            if (WidgetGlobalSearchDialog.showForQuery(childFragmentManager, route)) {
                StoreStream.getNavigation().consumePendingIntent(contract)
            }
            return
        }
        if (route is DeepLinkRoute.Discovery) {
            // `/discovery` opens directory search against the Discord-owned HQ directory channel.
            // Without this branch the route fell through to `else -> return`, so the intent stayed
            // queued forever and the app just showed the last-used tab.
            AppScreenRouter.launch(
                requireContext(),
                WidgetDirectoriesSearch::class.java,
                WidgetDirectoriesSearch.discoveryIntent(),
            )
            StoreStream.getNavigation().consumePendingIntent(contract)
            return
        }
        val tab = when (route) {
            DeepLinkRoute.Home -> NavigationTab.HOME
            DeepLinkRoute.Friends -> NavigationTab.FRIENDS
            is DeepLinkRoute.Settings -> NavigationTab.SETTINGS
            else -> return
        }
        if (tab == NavigationTab.HOME) {
            StoreStream.getTabsNavigation().selectHomeTab(StoreNavigation.PanelAction.CLOSE, true)
        } else {
            StoreStream.getTabsNavigation().selectTab(tab, true)
        }
        StoreStream.getNavigation().consumePendingIntent(contract)
    }

    private fun showTab(requestedTab: NavigationTab) {
        val visibleTabs = mainNavigationTabs
        val tab = requestedTab.takeIf { it in visibleTabs } ?: NavigationTab.HOME
        if (tab != requestedTab) StoreStream.getTabsNavigation().selectTab(tab)

        requireView().findViewById<View>(R.id.widget_tabs_host_home).visibility =
            if (tab == NavigationTab.HOME) View.VISIBLE else View.GONE
        destinations.forEach { (destinationTab, destination) ->
            val container = requireView().findViewById<ViewGroup>(destination.containerId)
            container.visibility = if (tab == destinationTab) View.VISIBLE else View.GONE
            if (tab == destinationTab && childFragmentManager.findFragmentByTag(tab.name) == null) {
                childFragmentManager.commit {
                    replace(
                        destination.containerId,
                        when (tab) {
                            NavigationTab.FRIENDS -> WidgetFriendsList()
                            NavigationTab.SEARCH -> WidgetGlobalSearch()
                            NavigationTab.MENTIONS -> WidgetUserMentions()
                            NavigationTab.SETTINGS -> WidgetSettings()
                            else -> instantiate(destination.className)
                        },
                        tab.name,
                    )
                }
            }
        }

        updateBottomNavigationVisibility(tab)
        updateBottomNavigation(tab)
    }

    private fun updateBottomNavigation(tab: NavigationTab = StoreStream.getTabsNavigation().getSelectedTab()) {
        val user = StoreStream.getUsers().getMe()
        bottomNavigation.updateView(
            tab,
            ::selectTab,
            true,
            user,
            StoreStream.getPresences().getPresences()[user.id],
            mainNavigationTabs,
            0,
            0,
            { selectTab(NavigationTab.SEARCH) },
            { WidgetUserStatusSheet.show(childFragmentManager) },
            {},
        )
    }

    private fun updateBottomNavigationVisibility(tab: NavigationTab = StoreStream.getTabsNavigation().getSelectedTab()) {
        bottomNavigation.visibility = if (shouldShowBottomNavigation(tab, leftPanelState)) View.VISIBLE else View.INVISIBLE
    }

    private fun activeTabContainerId() = when (StoreStream.getTabsNavigation().getSelectedTab()) {
        NavigationTab.FRIENDS -> R.id.widget_tabs_host_friends
        NavigationTab.SEARCH -> R.id.widget_tabs_host_search
        NavigationTab.SETTINGS -> R.id.widget_tabs_host_user_settings
        NavigationTab.MENTIONS -> R.id.widget_tabs_host_mentions
        else -> R.id.widget_tabs_host_home
    }

    /**
     * Selecting home always moves the panels: it closes them only when the user re-taps home while
     * the left panel is already open, and otherwise opens the left panel. That is what makes both
     * "tap home from another tab" and "press back from another tab" land on the guild/channel list
     * rather than on whatever chat was last open.
     */
    private fun selectTab(tab: NavigationTab) {
        if (tab != NavigationTab.HOME) {
            StoreStream.getTabsNavigation().selectTab(tab, true)
            return
        }
        val wasOnHome = StoreStream.getTabsNavigation().getSelectedTab() == NavigationTab.HOME
        val panelAction = if (wasOnHome && leftPanelState == PanelState.Open) {
            StoreNavigation.PanelAction.CLOSE
        } else {
            StoreNavigation.PanelAction.OPEN
        }
        StoreStream.getTabsNavigation().selectHomeTab(panelAction, true)
    }

    private fun instantiate(className: String) = childFragmentManager.fragmentFactory
        .instantiate(requireContext().classLoader, className)

    /**
     * The tabs host gets the first look at every back press.
     *
     * It consumes the press only while the user is somewhere it can step back from: an in-tab
     * screen pushed onto its child back stack, or any tab other than home. On the home tab it
     * declines, which hands the press to [com.discord.widgets.home.WidgetHome] and its panels.
     */
    private fun handleBackPress(): Boolean {
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack()
            return true
        }
        if (StoreStream.getTabsNavigation().getSelectedTab() == NavigationTab.HOME) return false
        selectTab(NavigationTab.HOME)
        return true
    }

    private data class Destination(val containerId: Int, val className: String)

    companion object {
        private const val STATE_SELECTED_TAB = "selected_tab"
        internal var homeFragmentFactory: (() -> Fragment)? = null
    }
}
