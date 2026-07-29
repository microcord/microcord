package com.discord.widgets.servers

import com.discord.api.user.User
import com.discord.models.domain.ModelBan
import com.discord.models.guild.Guild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSettingsBansViewModelTest {

    @Test
    fun `ban list distinguishes loading empty and filtered results`() {
        val guild = Guild(id = 1, name = "Guild", ownerId = 7)

        val loading = state(guild, null, "")
        assertTrue(loading.isLoading)

        val empty = state(guild, emptyMap(), "")
        assertFalse(empty.isLoading)
        assertEquals(0, empty.totalBannedUsers)

        val bans = listOf(ban(2, "Zulu"), ban(3, "alpha")).associateBy { it.user.id }
        val filtered = state(guild, bans, "AL")
        assertEquals(2, filtered.totalBannedUsers)
        assertEquals(listOf("alpha"), filtered.bans.map { it.ban.user.username })
    }

    private fun state(
        guild: Guild,
        bans: Map<Long, ModelBan>?,
        filter: String,
    ) = ServerSettingsBansViewModel.toViewState(
        ServerSettingsBansViewModel.StoreState(guild, canManageBans = true, bans = bans),
        filter,
    ) as ServerSettingsBansViewModel.ViewState.Loaded

    private fun ban(id: Long, username: String) = ModelBan(1, User(id, username))
}
