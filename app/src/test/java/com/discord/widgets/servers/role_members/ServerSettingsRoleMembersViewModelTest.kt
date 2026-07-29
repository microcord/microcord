package com.discord.widgets.servers.role_members

import com.discord.models.member.GuildMember
import com.discord.models.user.CoreUser
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerSettingsRoleMembersViewModelTest {

    @Test
    fun `filter keeps only role members matching username or nickname`() {
        val users = mapOf(
            1L to CoreUser(1, "Alpha"),
            2L to CoreUser(2, "Beta"),
            3L to CoreUser(3, "Gamma"),
        )
        val members = listOf(
            member(1, ROLE_ID),
            member(2, ROLE_ID, "Captain"),
            member(3, 99),
            member(4, ROLE_ID),
        )

        assertEquals(
            listOf(1L, 2L),
            ServerSettingsRoleMembersViewModel
                .filterMembers(members, users, ROLE_ID, "a")
                .map(GuildMember::userId),
        )
        assertEquals(
            listOf(2L),
            ServerSettingsRoleMembersViewModel
                .filterMembers(members, users, ROLE_ID, "capt")
                .map(GuildMember::userId),
        )
    }

    private fun member(userId: Long, roleId: Long, nick: String? = null) =
        GuildMember(0, roleId, listOf(roleId), nick, null, false, null, 1, userId)

    private companion object {
        const val ROLE_ID = 42L
    }
}
