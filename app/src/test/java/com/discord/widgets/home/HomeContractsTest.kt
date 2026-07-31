package com.discord.widgets.home

import com.discord.panels.OverlappingPanelsLayout.LockState
import com.discord.panels.PanelState
import com.discord.stores.StoreChannelsSelected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeContractsTest {

    @Test
    fun `left panel opens only when guild and channel are both absent`() {
        assertEquals(
            LockState.OPEN,
            LeftPanelManager.lockState(StoreChannelsSelected.ResolvedSelectedChannel.Unselected, 0L),
        )
        assertEquals(
            LockState.UNLOCKED,
            LeftPanelManager.lockState(StoreChannelsSelected.ResolvedSelectedChannel.Unselected, 1L),
        )
    }

    @Test
    fun `channel onboarding appears only after opening the panel for an eligible guild`() {
        assertTrue(
            WidgetHomeViewModel.shouldShowChannelOnboardingSheet(
                PanelState.Opening,
                PanelState.Open,
                guildId = 1L,
                isNewUser = true,
                hasUserSeenChannelOnboarding = false,
            ),
        )
        assertFalse(
            WidgetHomeViewModel.shouldShowChannelOnboardingSheet(
                PanelState.Opening,
                PanelState.Open,
                guildId = 1L,
                isNewUser = true,
                hasUserSeenChannelOnboarding = true,
            ),
        )
        assertFalse(
            WidgetHomeViewModel.shouldShowChannelOnboardingSheet(
                PanelState.Closed,
                PanelState.Open,
                guildId = 1L,
                isNewUser = true,
                hasUserSeenChannelOnboarding = false,
            ),
        )
    }
}
