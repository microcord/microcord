package com.discord.widgets.guilds.join

import com.discord.api.guild.welcome.GuildWelcomeScreen
import com.discord.models.guild.Guild
import com.discord.stores.StoreGuildWelcomeScreens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GuildJoinFlowTest {
    @Test
    fun `invite input accepts codes and Discord invite URLs only`() {
        assertEquals("abc_123", WidgetGuildJoin.inviteCode("abc_123"))
        assertEquals("abc-123", WidgetGuildJoin.inviteCode(" https://discord.gg/abc-123 "))
        assertEquals("code", WidgetGuildJoin.inviteCode("https://discord.com/invite/code"))
        assertEquals(null, WidgetGuildJoin.inviteCode("https://example.com/invite/code"))
        assertEquals(null, WidgetGuildJoin.inviteCode("not a code"))
    }

    @Test
    fun `welcome state requires a guild and handles fetch failure`() {
        assertEquals(
            WidgetGuildWelcomeSheetViewModel.ViewState.Loaded(
                1,
                "Guild",
                "G",
                null,
                null,
                null,
            ),
            WidgetGuildWelcomeSheetViewModel.reduce(
                WidgetGuildWelcomeSheetViewModel.StoreState(Guild(id = 1, name = "Guild"), null),
            ),
        )
        assertSame(
            WidgetGuildWelcomeSheetViewModel.ViewState.Invalid,
            WidgetGuildWelcomeSheetViewModel.reduce(
                WidgetGuildWelcomeSheetViewModel.StoreState(
                    Guild(id = 1, name = "Guild"),
                    StoreGuildWelcomeScreens.State.Failure,
                ),
            ),
        )
        assertEquals(
            WidgetGuildWelcomeSheetViewModel.ViewState.Loaded(
                1,
                "Guild",
                "G",
                null,
                "Welcome",
                emptyList(),
            ),
            WidgetGuildWelcomeSheetViewModel.reduce(
                WidgetGuildWelcomeSheetViewModel.StoreState(
                    Guild(id = 1, name = "Guild"),
                    StoreGuildWelcomeScreens.State.Loaded(GuildWelcomeScreen("Welcome", emptyList())),
                ),
            ),
        )
    }
}
