package com.discord.widgets.user

import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import org.junit.Assert.assertEquals
import org.junit.Test

class UserMutualGuildsManagerTest {
    @Test
    fun resolvesOnlyMembershipsInGuildDisplayOrder() {
        val first = Guild(id = 10, name = "First")
        val second = Guild(id = 20, name = "Second")
        val members = mapOf(
            first.id to mapOf(1L to member(first.id, 1)),
            second.id to mapOf(1L to member(second.id, 1), 2L to member(second.id, 2)),
        )

        val result = UserMutualGuildsManager.resolve(listOf(1L, 2L, 3L), listOf(second, first), members)

        assertEquals(listOf(second, first), result.getValue(1L))
        assertEquals(listOf(second), result.getValue(2L))
        assertEquals(emptyList<Guild>(), result.getValue(3L))
    }

    private fun member(guildId: Long, userId: Long) = GuildMember(
        color = 0,
        hoistRoleId = 0,
        roles = emptyList(),
        nick = null,
        premiumSince = null,
        pending = false,
        joinedAt = null,
        guildId = guildId,
        userId = userId,
    )
}
