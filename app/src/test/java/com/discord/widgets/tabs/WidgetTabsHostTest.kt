package com.discord.widgets.tabs

import com.discord.panels.PanelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetTabsHostTest {

    @Test
    fun bottomNavigationTracksTabAndLeftPanel() {
        assertFalse(TabsHostViewModel.shouldShowBottomNav(NavigationTab.HOME, PanelState.Closed))
        assertFalse(TabsHostViewModel.shouldShowBottomNav(NavigationTab.HOME, PanelState.Closing))
        assertTrue(TabsHostViewModel.shouldShowBottomNav(NavigationTab.HOME, PanelState.Opening))
        assertTrue(TabsHostViewModel.shouldShowBottomNav(NavigationTab.HOME, PanelState.Open))
        assertTrue(TabsHostViewModel.shouldShowBottomNav(NavigationTab.FRIENDS, PanelState.Closed))
        assertTrue(TabsHostViewModel.shouldShowBottomNav(NavigationTab.SETTINGS, PanelState.Closed))
    }

    /**
     * The tab strip is driven by guild membership, not fixed at five: search and mentions only
     * appear once the user is in at least one guild.
     */
    @Test
    fun visibleTabsFollowGuildCount() {
        assertEquals(
            setOf(NavigationTab.HOME, NavigationTab.FRIENDS, NavigationTab.SETTINGS),
            TabsHostViewModel.visibleTabsFor(guildCount = 0),
        )
        assertEquals(
            setOf(
                NavigationTab.HOME,
                NavigationTab.FRIENDS,
                NavigationTab.SEARCH,
                NavigationTab.MENTIONS,
                NavigationTab.SETTINGS,
            ),
            TabsHostViewModel.visibleTabsFor(guildCount = 1),
        )
    }

    @Test
    fun navigationTabOrdinalsMatchTheReference() {
        assertEquals(
            listOf(
                NavigationTab.HOME,
                NavigationTab.FRIENDS,
                NavigationTab.SEARCH,
                NavigationTab.MENTIONS,
                NavigationTab.SETTINGS,
                NavigationTab.DISCOVERY,
            ),
            TabsHostViewModel.TAB_DESTINATIONS,
        )
        assertFalse(NavigationTab.HOME in TabsHostViewModel.NON_HOME_TAB_DESTINATIONS)
        assertEquals(5, TabsHostViewModel.NON_HOME_TAB_DESTINATIONS.size)
    }
}
