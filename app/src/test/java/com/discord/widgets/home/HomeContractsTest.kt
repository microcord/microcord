package com.discord.widgets.home

import com.discord.panels.OverlappingPanelsLayout.LockState
import com.discord.panels.PanelState
import com.discord.stores.StoreChannelsSelected
import com.discord.api.channel.Channel
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
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

    @Test
    fun `thread only parents use browser while posts and drafts use chat`() {
        val forum = channel(Channel.GUILD_FORUM)
        val media = channel(Channel.GUILD_MEDIA)
        val post = channel(Channel.PUBLIC_THREAD)

        assertEquals(
            WidgetHome.Companion.CenterSurface.FORUM,
            WidgetHome.centerSurface(StoreChannelsSelected.ResolvedSelectedChannel.Channel(forum)),
        )
        assertEquals(
            WidgetHome.Companion.CenterSurface.FORUM,
            WidgetHome.centerSurface(StoreChannelsSelected.ResolvedSelectedChannel.Channel(media)),
        )
        assertEquals(
            WidgetHome.Companion.CenterSurface.CHAT,
            WidgetHome.centerSurface(StoreChannelsSelected.ResolvedSelectedChannel.Channel(post)),
        )
        assertEquals(
            WidgetHome.Companion.CenterSurface.CHAT,
            WidgetHome.centerSurface(StoreChannelsSelected.ResolvedSelectedChannel.ThreadDraft(forum)),
        )
        assertEquals(
            WidgetHome.Companion.CenterSurface.NONE,
            WidgetHome.centerSurface(StoreChannelsSelected.ResolvedSelectedChannel.Unselected),
        )
    }

    private fun channel(type: Int): Channel = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()
        .fromJson("""{"id":1,"guild_id":2,"type":$type,"name":"channel"}""", Channel::class.java)
}
