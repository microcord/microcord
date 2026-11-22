package com.discord.models.member

import android.graphics.Color
import com.discord.api.guildmember.GuildMember as ApiGuildMember
import com.discord.api.premium.PremiumTier
import com.discord.api.role.GuildRole
import com.discord.api.role.GuildRoleColors
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.guild.UserGuildMember
import com.discord.models.user.CoreUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuildMemberTest {
    @Test
    fun preservesReferenceDefaultsAndDerivedState() {
        val empty = GuildMember()
        assertEquals(emptyList<Long>(), empty.getRoles())
        assertFalse(empty.hasAvatar())
        assertFalse(empty.hasBanner())
        assertFalse(empty.isCommunicationDisabled())
        assertEquals(0, GuildMember.getColor(empty, 7))

        val member = GuildMember(
            Color.RED,
            9L,
            listOf(3L, 4L),
            "Nick",
            "premium",
            true,
            UtcDateTime(5),
            1L,
            2L,
            "avatar",
            "banner",
            "bio",
            UtcDateTime(Long.MAX_VALUE),
        )
        val user = CoreUser(2L, "User", premiumTier = PremiumTier.NONE)

        assertEquals(Color.RED, member.color)
        assertEquals(9L, member.hoistRoleId)
        assertEquals(listOf(3L, 4L), member.getRoles())
        assertEquals("Nick", member.nick)
        assertEquals("premium", member.premiumSince)
        assertTrue(member.pending)
        assertEquals(UtcDateTime(5), member.joinedAt)
        assertEquals(1L, member.guildId)
        assertEquals(2L, member.userId)
        assertEquals("avatar", member.avatarHash)
        assertEquals("banner", member.bannerHash)
        assertEquals("bio", member.bio)
        assertTrue(member.hasAvatar())
        assertTrue(member.hasBanner())
        assertTrue(member.isCommunicationDisabled())
        assertEquals(member, member.copy())
        assertEquals(member.hashCode(), member.copy().hashCode())

        val userMember = UserGuildMember(user, member)
        assertEquals("Nick", userMember.nickname)
        assertEquals("Nick", userMember.getNickOrUserName())
        assertEquals(userMember, userMember.copy())
        assertNull(UserGuildMember(user).nickname)
        assertEquals("User", UserGuildMember(user).getNickOrUserName())
    }

    @Test
    fun preservesNullBackedRolesAndReferenceNameOrdering() {
        val nullRoles = GuildMember()
        val emptyRoles = GuildMember(0, 0, emptyList(), null, null, false, null, 0, 0)
        assertEquals(emptyList<Long>(), nullRoles.getRoles())
        assertEquals(emptyList<Long>(), emptyRoles.getRoles())
        assertNotEquals(nullRoles, emptyRoles)
        assertTrue(nullRoles.toString().contains("roles=null"))

        val alpha2 = CoreUser(2L, "alpha")
        val alpha1 = CoreUser(1L, "ALPHA")
        val blankNick = nullRoles.copy(nick = "   ")
        assertTrue(GuildMember.compareUserNames(alpha2, alpha1, blankNick, null) > 0)
        assertFalse(nullRoles.copy(avatarHash = "avatar", guildId = 1).hasAvatar())
        assertFalse(nullRoles.copy(bannerHash = "banner", userId = 1).hasBanner())
        assertFalse(nullRoles.copy(communicationDisabledUntil = UtcDateTime(Long.MIN_VALUE)).isCommunicationDisabled())
    }

    @Test
    fun takesTheColourRolesGradient() {
        val gradient = GuildRoleColors(0x112233, 0x445566, null)
        val roles = mapOf(
            1L to GuildRole(2, "top", 1L, 0x112233, false, 0L, false, false, null, null, null, gradient),
            2L to GuildRole(1, "low", 2L, 0x778899, false, 0L, false, false, null, null, null, GuildRoleColors(0x778899, 0x1, null)),
            3L to GuildRole(3, "plain", 3L, 0, false, 0L, false, false, null, null, null, GuildRoleColors(0, null, null)),
        )
        val api = ApiGuildMember(
            guildId = 9,
            user = User(id = 5, username = "user"),
            roles = listOf(1L, 2L, 3L),
            nick = null,
            premiumSince = null,
            joinedAt = UtcDateTime(0),
            pending = false,
            presence = null,
            userId = 5,
            avatar = null,
            bio = null,
            banner = null,
            communicationDisabledUntil = null,
        )

        assertEquals(gradient, GuildMember.from(api, 9, roles).roleColors)
        assertNull(GuildMember.from(api, 9, roles.mapValues { it.value.copy(colors = GuildRoleColors(1, null, null)) }).roleColors)
    }
}
