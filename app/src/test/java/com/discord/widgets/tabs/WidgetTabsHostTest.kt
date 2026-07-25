package com.discord.widgets.tabs

import com.discord.panels.PanelState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetTabsHostTest {
    @Test
    fun bottomNavigationTracksTabAndLeftPanel() {
        assertFalse(shouldShowBottomNavigation(NavigationTab.HOME, PanelState.Closed))
        assertTrue(shouldShowBottomNavigation(NavigationTab.HOME, PanelState.Opening))
        assertTrue(shouldShowBottomNavigation(NavigationTab.HOME, PanelState.Open))
        assertTrue(shouldShowBottomNavigation(NavigationTab.FRIENDS, PanelState.Closed))
    }

    @Test
    fun bottomNavigationHasExactlyTheReferenceFiveDestinations() {
        assertEquals(
            setOf(NavigationTab.HOME, NavigationTab.FRIENDS, NavigationTab.SEARCH, NavigationTab.MENTIONS, NavigationTab.SETTINGS),
            mainNavigationTabs,
        )
    }
}
