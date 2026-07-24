package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import com.discord.widgets.tabs.NavigationTab
import rx.Observable
import rx.subjects.PublishSubject

class StoreTabsNavigation internal constructor(
    private val dispatcher: Dispatcher,
    private val storeStream: StoreStream,
    private val observationDeck: ObservationDeck,
) : StoreV2() {
    private var selectedTab = NavigationTab.HOME
    private var selectedTabSnapshot = selectedTab
    private val dismissTabsDialogEventSubject = PublishSubject.create<Unit>()

    fun getSelectedTab(): NavigationTab = selectedTabSnapshot

    fun observeSelectedTab(): Observable<NavigationTab> = observationDeck.connectRx(this) { selectedTabSnapshot }
        .distinctUntilChanged()

    fun observeDismissTabsDialogEvent(): Observable<Unit> = dismissTabsDialogEventSubject

    @JvmOverloads
    fun selectHomeTab(panelAction: StoreNavigation.PanelAction, dismissTabsDialogs: Boolean = false) =
        dispatcher.schedule {
            handleTabSelection(NavigationTab.HOME)
            storeStream.handleHomeTabSelected(panelAction)
            if (dismissTabsDialogs) dismissTabsDialogEventSubject.onNext(Unit)
        }

    @JvmOverloads
    fun selectTab(navigationTab: NavigationTab, dismissTabsDialogs: Boolean = false) =
        dispatcher.schedule { selectTabInternal(navigationTab, dismissTabsDialogs) }

    @StoreThread
    internal fun selectTabInternal(navigationTab: NavigationTab, dismissTabsDialogs: Boolean = false) {
        handleTabSelection(navigationTab)
        if (dismissTabsDialogs) dismissTabsDialogEventSubject.onNext(Unit)
    }

    @StoreThread
    internal fun handlePreLogout() = selectTabInternal(NavigationTab.HOME)

    @StoreThread
    private fun handleTabSelection(navigationTab: NavigationTab) {
        if (selectedTab == navigationTab) return
        selectedTab = navigationTab
        markChanged()
    }

    override fun snapshotData() {
        selectedTabSnapshot = selectedTab
    }
}
