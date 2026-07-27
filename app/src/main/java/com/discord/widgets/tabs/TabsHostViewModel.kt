package com.discord.widgets.tabs

import androidx.annotation.MainThread
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.guild.Guild
import com.discord.panels.PanelState
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreMentions
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.stores.StoreTabsNavigation
import com.discord.stores.StoreUser
import com.discord.stores.StoreUserRelationships
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject

/**
 * State for the bottom tab bar: which tab is selected, which tabs exist, their badge counts, and
 * whether the bar is on screen at all.
 *
 * Three inputs that are easy to miss:
 *
 * * **The tab set depends on guild membership.** A user in no guild gets
 *   [NO_GUILD_TABS] — home, friends, settings — because search and mentions have nothing to
 *   operate on. The moment they join one they get [AT_LEAST_ONE_GUILD_TABS].
 * * **The bar hides itself on the home tab** unless the left panel is open or opening. On home the
 *   chat surface is full bleed and the bar would cover the composer; the guild/channel list is the
 *   only home state that has room for it.
 * * **The friends badge counts incoming friend requests**, i.e. relationships of type
 *   [ModelUserRelationship.TYPE_INVITE_INCOMING], not friends.
 */
class TabsHostViewModel(
    bottomNavViewObserver: BottomNavViewObserver = BottomNavViewObserver.INSTANCE,
    private val storeTabsNavigation: StoreTabsNavigation = StoreStream.getTabsNavigation(),
    private val storeNavigation: StoreNavigation = StoreStream.getNavigation(),
    storeStateObservable: Observable<StoreState> = observeStoreState(),
) : AppViewModel<TabsHostViewModel.ViewState>(
    ViewState(
        selectedTab = NavigationTab.HOME,
        showBottomNav = false,
        bottomNavHeight = 0,
        myUserId = 0L,
        visibleTabs = NO_GUILD_TABS,
        numHomeNotifications = 0,
        numFriendsNotifications = 0,
    ),
) {

    data class StoreState(
        val leftPanelState: PanelState,
        val selectedTab: NavigationTab,
        val myUserId: Long,
        val guildIdToGuildMap: Map<Long, Guild>,
        val numTotalMentions: Int,
        val userRelationships: Map<Long, Int>,
    )

    data class ViewState(
        val selectedTab: NavigationTab,
        val showBottomNav: Boolean,
        val bottomNavHeight: Int,
        val myUserId: Long,
        val visibleTabs: Set<NavigationTab>,
        val numHomeNotifications: Int,
        val numFriendsNotifications: Int,
    )

    sealed class Event {
        /** The friends tab just became visible; the analytics event fires once per selection. */
        object TrackFriendsListShown : Event()

        /** Something navigated, so the quick-switcher dialog must not stay open over the result. */
        object DismissSearchDialog : Event()
    }

    private val eventSubject: PublishSubject<Event> = PublishSubject.create()
    private var storeState: StoreState? = null

    init {
        storeStateObservable
            .computationLatest()
            .ui(this)
            .appSubscribe(TabsHostViewModel::class.java) { handleStoreState(it) }

        bottomNavViewObserver.observeHeight()
            .ui(this)
            .appSubscribe(TabsHostViewModel::class.java) { handleBottomNavHeight(it) }

        storeTabsNavigation.observeDismissTabsDialogEvent()
            .ui(this)
            .appSubscribe(TabsHostViewModel::class.java) { dismissSearchDialog() }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    @MainThread
    fun selectTab(tab: NavigationTab) {
        val previouslySelectedTab = requireViewState().selectedTab
        storeTabsNavigation.selectTab(tab)
        when (tab) {
            // Selecting home closes the panels only when the user re-taps home while the left panel
            // is already open; every other route into home opens it, which is what makes both
            // "tap home from another tab" and "back out of another tab" land on the guild list
            // rather than on whatever chat was last open.
            NavigationTab.HOME -> {
                val panelAction =
                    if (previouslySelectedTab == NavigationTab.HOME && storeState?.leftPanelState == PanelState.Open) {
                        StoreNavigation.PanelAction.CLOSE
                    } else {
                        StoreNavigation.PanelAction.OPEN
                    }
                storeNavigation.setNavigationPanelAction(panelAction)
            }
            NavigationTab.FRIENDS -> emitTrackFriendsListShown()
            else -> Unit
        }
    }

    /**
     * Consumes a back press while the user is on a tab they can step back from. Home declines, which
     * hands the press on to [com.discord.widgets.home.WidgetHome] and its panels.
     */
    @MainThread
    fun handleBackPress(): Boolean {
        val selectedTab = requireViewState().selectedTab
        if (selectedTab !in TAB_DESTINATIONS || selectedTab == NavigationTab.HOME) return false
        selectTab(NavigationTab.HOME)
        return true
    }

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        this.storeState = storeState
        val selectedTab = storeState.selectedTab
        val showBottomNav = shouldShowBottomNav(selectedTab, storeState.leftPanelState)
        val visibleTabs = visibleTabsFor(storeState.guildIdToGuildMap.size)
        val numFriendsNotifications = storeState.userRelationships
            .count { (_, type) -> type == ModelUserRelationship.TYPE_INVITE_INCOMING }
        updateViewState(
            requireViewState().copy(
                selectedTab = selectedTab,
                showBottomNav = showBottomNav,
                myUserId = storeState.myUserId,
                visibleTabs = visibleTabs,
                numHomeNotifications = storeState.numTotalMentions,
                numFriendsNotifications = numFriendsNotifications,
            ),
        )
    }

    @MainThread
    private fun handleBottomNavHeight(height: Int) {
        updateViewState(requireViewState().copy(bottomNavHeight = height))
    }

    @MainThread
    private fun emitTrackFriendsListShown() = eventSubject.onNext(Event.TrackFriendsListShown)

    @MainThread
    private fun dismissSearchDialog() = eventSubject.onNext(Event.DismissSearchDialog)

    companion object {
        /** Every tab, in ordinal order. Used only to decide whether a back press is ours to take. */
        val TAB_DESTINATIONS: List<NavigationTab> = NavigationTab.entries.toList()

        val NON_HOME_TAB_DESTINATIONS: List<NavigationTab> = TAB_DESTINATIONS - NavigationTab.HOME

        val NO_GUILD_TABS: Set<NavigationTab> =
            setOf(NavigationTab.HOME, NavigationTab.FRIENDS, NavigationTab.SETTINGS)

        val AT_LEAST_ONE_GUILD_TABS: Set<NavigationTab> = setOf(
            NavigationTab.HOME,
            NavigationTab.FRIENDS,
            NavigationTab.SEARCH,
            NavigationTab.MENTIONS,
            NavigationTab.SETTINGS,
        )

        /**
         * The bar is visible on every tab but home; on home only while the left panel is resting
         * open or on its way there, because that is the only home state with room for it.
         */
        fun shouldShowBottomNav(selectedTab: NavigationTab, leftPanelState: PanelState): Boolean =
            selectedTab in NON_HOME_TAB_DESTINATIONS ||
                (
                    selectedTab == NavigationTab.HOME &&
                        (leftPanelState == PanelState.Open || leftPanelState == PanelState.Opening)
                    )

        /** Search and mentions have nothing to operate on until the user is in at least one guild. */
        fun visibleTabsFor(guildCount: Int): Set<NavigationTab> =
            if (guildCount > 0) AT_LEAST_ONE_GUILD_TABS else NO_GUILD_TABS

        fun observeStoreState(
            storeNavigation: StoreNavigation = StoreStream.getNavigation(),
            storeTabsNavigation: StoreTabsNavigation = StoreStream.getTabsNavigation(),
            storeUser: StoreUser = StoreStream.getUsers(),
            storeGuilds: StoreGuilds = StoreStream.getGuilds(),
            storeMentions: StoreMentions = StoreStream.getMentions(),
            storeUserRelationships: StoreUserRelationships = StoreStream.getUserRelationships(),
        ): Observable<StoreState> = Observable.combineLatest(
            storeNavigation.observeLeftPanelState(),
            storeTabsNavigation.observeSelectedTab(),
            storeUser.observeMeId(),
            storeGuilds.observeGuilds(),
            storeMentions.observeTotalMentions(),
            storeUserRelationships.observe(),
        ) { leftPanelState, selectedTab, myUserId, guildIdToGuildMap, numTotalMentions, userRelationships ->
            StoreState(
                leftPanelState = leftPanelState,
                selectedTab = selectedTab,
                myUserId = myUserId,
                guildIdToGuildMap = guildIdToGuildMap,
                numTotalMentions = numTotalMentions,
                userRelationships = userRelationships,
            )
        }
    }
}
