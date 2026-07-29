package com.discord.widgets.tabs

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.Lifecycle
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.intent.AppIntentContract
import com.discord.utilities.intent.DeepLinkRoute
import com.discord.utilities.intent.LinkParseResult
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.setForwardingWindowInsetsListener
import com.discord.widgets.auth.WidgetOauth2Authorize
import com.discord.widgets.auth.WidgetRemoteAuth
import com.discord.widgets.channels.memberlist.WidgetChannelMembersList
import com.discord.widgets.directories.WidgetDirectoriesSearch
import com.discord.widgets.friends.WidgetFriendsAdd
import com.discord.widgets.friends.WidgetFriendsAddById
import com.discord.widgets.friends.WidgetFriendsList
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.settings.WidgetSettings
import com.discord.widgets.settings.account.WidgetSettingsBlockedUsers
import com.discord.widgets.status.WidgetGlobalStatusIndicatorState
import com.discord.widgets.user.WidgetUserMentions
import com.discord.widgets.user.WidgetUserMutualFriends
import com.discord.widgets.user.WidgetUserMutualGuilds
import com.discord.widgets.user.WidgetUserSetCustomStatus
import com.discord.widgets.user.WidgetUserStatusSheet
import com.discord.widgets.user.search.WidgetGlobalSearchDialog
import com.discord.widgets.user.usersheet.WidgetUserSheet
import rx.Subscription

/**
 * The signed-in shell: one fragment per tab, all kept alive, plus the bottom navigation bar.
 *
 * Tabs are shown and hidden rather than replaced, because the home surface owns the guild list, the
 * channel list and the chat, and re-creating it on every tab switch would lose all three scroll
 * positions. That is also why [OnTabSelectedListener] exists — a hidden-then-shown fragment gets no
 * lifecycle callback of its own.
 *
 * Search is deliberately not a fragment destination: tapping the search tab — or long-pressing the
 * mentions tab — opens [WidgetGlobalSearchDialog] over whichever tab is showing, which is what the
 * reference does. `R.id.widget_tabs_host_search` exists in the recovered layout but the reference
 * never populates it.
 */
class WidgetTabsHost : AppFragment(R.layout.widget_tabs_host) {

    private val viewModel: TabsHostViewModel by appViewModels { TabsHostViewModel() }

    private val globalStatusIndicatorStateObserver = WidgetGlobalStatusIndicatorState.Provider.get()
    private val tabToTabSelectionListenerMap = mutableMapOf<NavigationTab, OnTabSelectedListener>()

    private lateinit var container: ViewGroup
    private lateinit var navHost: FrameLayout
    private lateinit var nonFullscreenNavHost: FrameLayout
    private lateinit var homeContainer: View
    private lateinit var bottomNavigation: TabsHostBottomNavigationView

    private var bottomNavAnimator: ValueAnimator? = null
    private var previousShowBottomNav: Boolean? = null
    private var previousBottomNavHeight = 0
    private var viewState: TabsHostViewModel.ViewState? = null
    private var pendingIntentSubscription: Subscription? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        container = view.findViewById(R.id.widget_tabs_host_container)
        navHost = view.findViewById(R.id.widget_tabs_host_nav_host)
        nonFullscreenNavHost = view.findViewById(R.id.widget_tabs_host_non_fullscreen_nav_host)
        homeContainer = view.findViewById(R.id.widget_tabs_host_home)
        bottomNavigation = view.findViewById(R.id.widget_tabs_host_bottom_navigation_view)
        // `widget_tabs_host_search` is a full-bleed container that the reference never fills —
        // search is a dialog, not a tab — so it is hidden once and left alone. Were it left visible
        // it would sit empty on top of whichever tab is actually selected.
        view.findViewById<View>(R.id.widget_tabs_host_search).visibility = View.GONE
        bottomNavigation.addHeightChangedListener(BottomNavViewObserver.INSTANCE)
        setPanelWindowInsetsListeners()
        // Registered before any child fragment is committed so the tabs host keeps the first look at
        // every back press. WidgetHome consumes every press it is offered, so it has to sort after
        // us or a back press on another tab would drive the hidden home panels instead.
        setOnBackPressed(viewModel::handleBackPress)
        installCrossScreenNavigation()
        installTabFragments()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .distinctUntilChanged()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetTabsHost::class.java) { updateViews(it) }
        viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetTabsHost::class.java) { handleEvent(it) }
        configureSystemStatusBar()
    }

    override fun onStart() {
        super.onStart()
        pendingIntentSubscription = StoreStream.getNavigation().observePendingIntent()
            .ui(this)
            .subscribe({ consumeOwnedRoute(it) }, {})
    }

    override fun onStop() {
        pendingIntentSubscription?.unsubscribe()
        pendingIntentSubscription = null
        super.onStop()
    }

    override fun onDestroyView() {
        bottomNavAnimator?.cancel()
        bottomNavAnimator = null
        clearCrossScreenNavigation()
        super.onDestroyView()
    }

    /**
     * Lets a tab's root fragment be told when its tab is selected. Called from the child's
     * `onViewBound`, which runs after ours, so the currently selected tab is replayed immediately —
     * otherwise a listener registered after the first render would stay silent until the user
     * switched away and back.
     */
    fun registerTabSelectionListener(navigationTab: NavigationTab, listener: OnTabSelectedListener) {
        tabToTabSelectionListenerMap[navigationTab] = listener
        viewState?.selectedTab?.let(::navigateToTab)
    }

    private fun updateViews(viewState: TabsHostViewModel.ViewState) {
        this.viewState = viewState
        navigateToTab(viewState.selectedTab)
        bottomNavigation.updateView(
            viewState.selectedTab,
            viewModel::selectTab,
            viewState.showBottomNav,
            viewState.myUserId,
            viewState.visibleTabs,
            viewState.numHomeNotifications,
            viewState.numFriendsNotifications,
            ::onSearchClick,
            ::onSettingsLongPress,
            // Long-pressing mentions opens the quick switcher, same as tapping search.
            ::onSearchClick,
        )
        updateNavHostMargins(viewState)
        animateBottomNav(viewState)
    }

    /**
     * The bar is drawn over the content, so every tab but home needs a bottom margin the height of
     * the bar. Home does not: its chat surface is full bleed and the bar floats over the panels.
     */
    private fun updateNavHostMargins(viewState: TabsHostViewModel.ViewState) {
        val layoutParams = navHost.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.bottomMargin =
            if (viewState.selectedTab == NavigationTab.HOME) 0 else viewState.bottomNavHeight
        navHost.layoutParams = layoutParams
    }

    private fun animateBottomNav(viewState: TabsHostViewModel.ViewState) {
        val showBottomNav = viewState.showBottomNav
        val bottomNavHeight = viewState.bottomNavHeight
        if (showBottomNav != previousShowBottomNav || bottomNavHeight != previousBottomNavHeight) {
            bottomNavAnimator?.cancel()
            val fromTranslationY = bottomNavigation.translationY
            bottomNavAnimator = when {
                showBottomNav -> {
                    bottomNavigation.visibility = View.VISIBLE
                    slideBottomNav(fromTranslationY, 0f, UPWARD_ANIMATION_DURATION_MS)
                }
                // Before the bar has been measured there is no distance to slide, so it is hidden
                // outright rather than animated to an unknown offset.
                bottomNavHeight > 0 ->
                    slideBottomNav(fromTranslationY, bottomNavHeight.toFloat(), DOWNWARD_ANIMATION_DURATION_MS)
                else -> {
                    bottomNavigation.visibility = View.INVISIBLE
                    null
                }
            }
        }
        previousBottomNavHeight = bottomNavHeight
        previousShowBottomNav = showBottomNav
    }

    private fun slideBottomNav(from: Float, to: Float, durationMs: Long): ValueAnimator =
        ValueAnimator.ofFloat(from, to).apply {
            interpolator = FastOutSlowInInterpolator()
            duration = durationMs
            addUpdateListener { bottomNavigation.translationY = it.animatedValue as Float }
            start()
        }

    private fun navigateToTab(navigationTab: NavigationTab) {
        TAB_CONTAINER_IDS.forEach { (tab, containerId) ->
            requireView().findViewById<View>(containerId).visibility =
                if (navigationTab == tab) View.VISIBLE else View.GONE
        }
        tabToTabSelectionListenerMap[navigationTab]?.onTabSelected()
    }

    private fun handleEvent(event: TabsHostViewModel.Event) = when (event) {
        TabsHostViewModel.Event.TrackFriendsListShown -> AnalyticsTracker.friendsListViewed()
        TabsHostViewModel.Event.DismissSearchDialog -> WidgetGlobalSearchDialog.dismiss(parentFragmentManager)
    }

    private fun onSearchClick() = WidgetGlobalSearchDialog.show(parentFragmentManager)

    private fun onSettingsLongPress() = WidgetUserStatusSheet.show(childFragmentManager)

    /**
     * The status bar matches the tertiary background that sits behind the tab content — except while
     * a call/stream indicator is showing, when the indicator paints its own colour all the way up
     * behind the status bar and must not be overwritten.
     */
    private fun configureSystemStatusBar() {
        val themedColor = ColorCompat.getThemedColor(this, R.attr.colorBackgroundTertiary)
        ColorCompat.setStatusBarColor(this, themedColor)
        globalStatusIndicatorStateObserver.observeState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetTabsHost::class.java) { state ->
                if (!state.isCustomBackground) ColorCompat.setStatusBarColor(this, themedColor)
            }
    }

    /**
     * The tab content draws under the system bars, so the insets are split by hand: horizontal
     * insets stay on the container, the vertical ones are forwarded to whichever child needs them,
     * and the bottom navigation bar takes the navigation-bar inset as padding.
     */
    private fun setPanelWindowInsetsListeners() {
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            view.setPadding(
                insets.systemWindowInsetLeft,
                view.paddingTop,
                insets.systemWindowInsetRight,
                view.paddingBottom,
            )
            val forwarded = WindowInsetsCompat.Builder()
                .setSystemWindowInsets(Insets.of(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom))
                .build()
            (view as? ViewGroup)?.children?.forEach { ViewCompat.dispatchApplyWindowInsets(it, forwarded) }
            forwarded
        }
        navHost.setForwardingWindowInsetsListener()
        // The home surface is padded down by the status bar here and then handed insets with the
        // top removed, so its panels only ever see the side and bottom insets.
        ViewCompat.setOnApplyWindowInsetsListener(homeContainer) { view, insets ->
            view.setPadding(view.paddingLeft, insets.systemWindowInsetTop, view.paddingRight, view.paddingBottom)
            val forwarded = WindowInsetsCompat.Builder()
                .setSystemWindowInsets(
                    Insets.of(
                        insets.systemWindowInsetLeft,
                        0,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom,
                    ),
                )
                .build()
            (view as? ViewGroup)?.children?.forEach { ViewCompat.dispatchApplyWindowInsets(it, forwarded) }
            forwarded
        }
        ViewCompat.setOnApplyWindowInsetsListener(nonFullscreenNavHost) { view, insets ->
            view.setPadding(view.paddingLeft, insets.systemWindowInsetTop, view.paddingRight, view.paddingBottom)
            insets.consumeSystemWindowInsets()
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomNavigation) { view, insets ->
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, insets.systemWindowInsetBottom)
            insets.consumeSystemWindowInsets()
        }
        container.requestApplyInsets()
    }

    private fun installTabFragments() {
        if (childFragmentManager.findFragmentById(R.id.widget_tabs_host_home) != null) return
        childFragmentManager.commit {
            replace(R.id.widget_tabs_host_home, homeFragmentFactory?.invoke() ?: WidgetHome(), NavigationTab.HOME.name)
            replace(R.id.widget_tabs_host_friends, WidgetFriendsList(), NavigationTab.FRIENDS.name)
            replace(R.id.widget_tabs_host_mentions, WidgetUserMentions(), NavigationTab.MENTIONS.name)
            replace(R.id.widget_tabs_host_user_settings, WidgetSettings(), NavigationTab.SETTINGS.name)
        }
    }

    /**
     * Cross-screen navigation callbacks.
     *
     * These have no reference analogue: the reference launches each of these screens as its own
     * activity through its screen router. Until every screen is routable that way, the tabs host —
     * the one fragment that outlives all of them — owns the wiring.
     */
    private fun installCrossScreenNavigation() {
        // `WidgetFriendsList` owns its own navigation now: `FriendsListViewModel` drives it, the
        // toolbar's add-friend item goes through `WidgetFriendsAdd.show` (which also emits the
        // "Add Friend Modal" analytics event), rows open `WidgetUserSheet` directly, and
        // `WidgetFriendsAddById` registers its own `WidgetCaptcha.registerForResult` launcher.
        WidgetChannelMembersList.onUserClicked = { userId ->
            withActiveChildManager { WidgetUserSheet.show(userId, it) }
        }
        WidgetUserStatusSheet.onCustomStatus = {
            withActiveChildManager { manager ->
                manager.commit {
                    replace(activeTabContainerId(), WidgetUserSetCustomStatus())
                    addToBackStack("custom_status")
                }
            }
        }
        WidgetUserSheet.onMutualFriends = { userId ->
            withActiveChildManager { manager ->
                manager.commit {
                    replace(activeTabContainerId(), WidgetUserMutualFriends.newInstance(userId))
                    addToBackStack("mutual_friends")
                }
            }
        }
        WidgetUserSheet.onMutualGuilds = { userId ->
            withActiveChildManager { manager ->
                manager.commit {
                    replace(activeTabContainerId(), WidgetUserMutualGuilds.newInstance(userId))
                    addToBackStack("mutual_guilds")
                }
            }
        }
        WidgetUserMutualFriends.onFriendSelected = { userId ->
            withActiveChildManager { WidgetUserSheet.show(userId, it) }
        }
        WidgetUserMutualGuilds.onGuildSelected = { guildId ->
            withActiveChildManager { manager ->
                manager.popBackStack()
                StoreStream.getTabsNavigation().selectTab(NavigationTab.HOME, true)
                StoreStream.selectGuild(guildId)
            }
        }
        WidgetSettings.onSetStatus = {
            withActiveChildManager(WidgetUserStatusSheet::show)
        }
        WidgetSettings.onBlockedUsers = {
            withActiveChildManager { manager ->
                manager.commit {
                    replace(R.id.widget_tabs_host_user_settings, WidgetSettingsBlockedUsers())
                    addToBackStack("blocked_users")
                }
            }
        }
        WidgetSettings.onLogout = { StoreStream.getAuthentication().logout() }
        WidgetSettingsBlockedUsers.onProfile = { user ->
            withActiveChildManager { WidgetUserSheet.show(user.id, it) }
        }
    }

    private inline fun withActiveChildManager(action: (FragmentManager) -> Unit) {
        if (!isAdded || view == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val manager = childFragmentManager
        if (!manager.isStateSaved) action(manager)
    }

    private fun clearCrossScreenNavigation() {
        WidgetChannelMembersList.onUserClicked = null
        WidgetUserStatusSheet.onCustomStatus = null
        WidgetUserSheet.onMutualFriends = null
        WidgetUserSheet.onMutualGuilds = null
        WidgetUserMutualFriends.onFriendSelected = null
        WidgetUserMutualGuilds.onGuildSelected = null
        WidgetSettings.onSetStatus = null
        WidgetSettings.onBlockedUsers = null
        WidgetSettings.onLogout = null
        WidgetSettingsBlockedUsers.onProfile = {}
    }

    private fun activeTabContainerId(): Int =
        TAB_CONTAINER_IDS[StoreStream.getTabsNavigation().getSelectedTab()] ?: R.id.widget_tabs_host_home

    /** Deep links whose destination is a tab, a profile, or a dialog this host owns. */
    private fun consumeOwnedRoute(contract: AppIntentContract) {
        val route = ((contract as? AppIntentContract.Link)?.result as? LinkParseResult.Internal)?.route ?: return
        when (route) {
            is DeepLinkRoute.User -> WidgetUserSheet.show(route.userId, childFragmentManager)
            // A `/query/` App Action names a destination rather than identifying one; the quick
            // switcher is what resolves a name, so the link opens it pre-filled.
            is DeepLinkRoute.Query ->
                if (!WidgetGlobalSearchDialog.showForQuery(childFragmentManager, route)) return
            // `/discovery` opens directory search against the Discord-owned HQ directory channel.
            is DeepLinkRoute.Discovery -> AppScreenRouter.launch(
                requireContext(),
                WidgetDirectoriesSearch::class.java,
                WidgetDirectoriesSearch.discoveryIntent(),
            )
            // A `/ra/<fingerprint>` link is a login waiting on another device; it opens the
            // remote-auth confirmation screen.
            is DeepLinkRoute.RemoteAuth -> WidgetRemoteAuth.launch(requireContext(), route.fingerprint)
            // `/oauth2/authorize` and `/connect/authorize` open the app-authorization screen.
            is DeepLinkRoute.Authorization -> WidgetOauth2Authorize.launch(
                requireContext(),
                WidgetOauth2Authorize.authorizeUri(route.parameters),
            )
            DeepLinkRoute.Home ->
                StoreStream.getTabsNavigation().selectHomeTab(StoreNavigation.PanelAction.CLOSE, true)
            DeepLinkRoute.Friends -> StoreStream.getTabsNavigation().selectTab(NavigationTab.FRIENDS, true)
            is DeepLinkRoute.Settings -> StoreStream.getTabsNavigation().selectTab(NavigationTab.SETTINGS, true)
            else -> return
        }
        StoreStream.getNavigation().consumePendingIntent(contract)
    }

    companion object {
        private const val UPWARD_ANIMATION_DURATION_MS = 250L
        private const val DOWNWARD_ANIMATION_DURATION_MS = 200L

        private val TAB_CONTAINER_IDS = mapOf(
            NavigationTab.HOME to R.id.widget_tabs_host_home,
            NavigationTab.FRIENDS to R.id.widget_tabs_host_friends,
            NavigationTab.MENTIONS to R.id.widget_tabs_host_mentions,
            NavigationTab.SETTINGS to R.id.widget_tabs_host_user_settings,
        )

        /** Test seam: lets an instrumentation test host a stand-in for the home surface. */
        internal var homeFragmentFactory: (() -> Fragment)? = null
    }
}
