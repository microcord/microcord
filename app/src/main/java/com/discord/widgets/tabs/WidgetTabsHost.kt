package com.discord.widgets.tabs

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.discord.R
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.intent.AppIntentContract
import com.discord.utilities.intent.DeepLinkRoute
import com.discord.utilities.intent.LinkParseResult
import com.discord.widgets.home.BackHandler
import com.discord.widgets.home.WidgetHome
import rx.Subscription

class WidgetTabsHost : Fragment(R.layout.widget_tabs_host), BackHandler {
    private lateinit var bottomNavigation: TabsHostBottomNavigationView
    private var selectedTabSubscription: Subscription? = null
    private var pendingIntentSubscription: Subscription? = null
    private var restoredTab: NavigationTab? = null
    private val destinations = mapOf(
        NavigationTab.FRIENDS to Destination(R.id.widget_tabs_host_friends, "com.discord.widgets.friends.WidgetFriendsList"),
        NavigationTab.MENTIONS to Destination(R.id.widget_tabs_host_mentions, "com.discord.widgets.user.WidgetUserMentions"),
        NavigationTab.SETTINGS to Destination(R.id.widget_tabs_host_user_settings, "com.discord.widgets.settings.WidgetSettings"),
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bottomNavigation = view.findViewById(R.id.widget_tabs_host_bottom_navigation_view)
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
    }

    override fun onStop() {
        selectedTabSubscription?.unsubscribe()
        selectedTabSubscription = null
        pendingIntentSubscription?.unsubscribe()
        pendingIntentSubscription = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_TAB, StoreStream.getTabsNavigation().getSelectedTab().name)
        super.onSaveInstanceState(outState)
    }

    private fun consumeOwnedRoute(contract: AppIntentContract) {
        val route = ((contract as? AppIntentContract.Link)?.result as? LinkParseResult.Internal)?.route ?: return
        val tab = when (route) {
            DeepLinkRoute.Home -> NavigationTab.HOME
            DeepLinkRoute.Friends -> NavigationTab.FRIENDS
            is DeepLinkRoute.Settings -> NavigationTab.SETTINGS
            else -> return
        }
        if (tab != NavigationTab.HOME && destinations[tab]?.let { isFragmentAvailable(it.className) } != true) return
        if (tab == NavigationTab.HOME) {
            StoreStream.getTabsNavigation().selectHomeTab(StoreNavigation.PanelAction.CLOSE, true)
        } else {
            StoreStream.getTabsNavigation().selectTab(tab, true)
        }
        StoreStream.getNavigation().consumePendingIntent(contract)
    }

    private fun showTab(requestedTab: NavigationTab) {
        val visibleTabs = buildSet {
            add(NavigationTab.HOME)
            destinations.filterValues { isFragmentAvailable(it.className) }.keys.forEach(::add)
        }
        val tab = requestedTab.takeIf { it in visibleTabs } ?: NavigationTab.HOME
        if (tab != requestedTab) StoreStream.getTabsNavigation().selectTab(tab)

        requireView().findViewById<View>(R.id.widget_tabs_host_home).visibility =
            if (tab == NavigationTab.HOME) View.VISIBLE else View.GONE
        destinations.forEach { (destinationTab, destination) ->
            val container = requireView().findViewById<ViewGroup>(destination.containerId)
            container.visibility = if (tab == destinationTab) View.VISIBLE else View.GONE
            if (tab == destinationTab && childFragmentManager.findFragmentByTag(tab.name) == null) {
                childFragmentManager.commit {
                    replace(destination.containerId, instantiate(destination.className), tab.name)
                }
            }
        }

        bottomNavigation.visibility = View.VISIBLE
        bottomNavigation.updateView(
            tab,
            ::selectTab,
            true,
            StoreStream.getUsers().getMe().id,
            visibleTabs,
            0,
            0,
            {},
            {},
            {},
        )
    }

    private fun selectTab(tab: NavigationTab) {
        if (tab == NavigationTab.HOME && StoreStream.getTabsNavigation().getSelectedTab() == NavigationTab.HOME) {
            StoreStream.getTabsNavigation().selectHomeTab(StoreNavigation.PanelAction.OPEN, true)
        } else {
            StoreStream.getTabsNavigation().selectTab(tab, true)
        }
    }

    private fun instantiate(className: String) = childFragmentManager.fragmentFactory
        .instantiate(requireContext().classLoader, className)

    private fun isFragmentAvailable(className: String) = runCatching {
        Class.forName(className, false, requireContext().classLoader).asSubclass(Fragment::class.java)
    }.isSuccess

    override fun onBackPressed(): Boolean {
        val selectedTab = StoreStream.getTabsNavigation().getSelectedTab()
        if (selectedTab != NavigationTab.HOME) {
            StoreStream.getTabsNavigation().selectTab(NavigationTab.HOME, true)
            return true
        }
        return (childFragmentManager.findFragmentByTag(NavigationTab.HOME.name) as? BackHandler)
            ?.onBackPressed() == true
    }

    private data class Destination(val containerId: Int, val className: String)

    companion object {
        private const val STATE_SELECTED_TAB = "selected_tab"
        internal var homeFragmentFactory: (() -> Fragment)? = null
    }
}
