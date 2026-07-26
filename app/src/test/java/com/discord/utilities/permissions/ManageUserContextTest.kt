package com.discord.utilities.permissions

import com.discord.api.premium.PremiumTier
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.api.user.NsfwAllowance
import com.discord.models.guild.Guild
import com.discord.models.user.CoreUser
import com.discord.models.user.MeUser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageUserContextTest {

    @Test fun `kicking requires outranking the target`() {
        val outranking = context(
            actingRoles = listOf(HIGH_ROLE), targetRoles = listOf(LOW_ROLE),
            permissions = Permission.KICK_MEMBERS,
        )
        val outranked = context(
            actingRoles = listOf(LOW_ROLE), targetRoles = listOf(HIGH_ROLE),
            permissions = Permission.KICK_MEMBERS,
        )
        val sameRank = context(
            actingRoles = listOf(HIGH_ROLE), targetRoles = listOf(HIGH_ROLE),
            permissions = Permission.KICK_MEMBERS,
        )

        assertTrue(outranking.canKick)
        assertFalse(outranked.canKick)
        assertFalse(sameRank.canKick)
    }

    @Test fun `nobody may moderate the guild owner`() {
        val context = context(
            actingRoles = listOf(HIGH_ROLE), targetRoles = emptyList(),
            permissions = Permission.BAN_MEMBERS, targetId = OWNER,
        )
        assertFalse(context.canBan)
    }

    @Test fun `nobody may moderate themselves`() {
        val context = context(
            actingRoles = listOf(HIGH_ROLE), targetRoles = listOf(HIGH_ROLE),
            permissions = Permission.KICK_MEMBERS or Permission.BAN_MEMBERS, targetId = ACTOR,
        )
        assertFalse(context.canKick)
        assertFalse(context.canBan)
    }

    @Test fun `moderation is blocked when the guild requires MFA and the user has none`() {
        val withoutMfa = context(
            actingRoles = listOf(HIGH_ROLE), targetRoles = listOf(LOW_ROLE),
            permissions = Permission.BAN_MEMBERS, mfaLevel = 1, mfaEnabled = false,
        )
        val withMfa = context(
            actingRoles = listOf(HIGH_ROLE), targetRoles = listOf(LOW_ROLE),
            permissions = Permission.BAN_MEMBERS, mfaLevel = 1, mfaEnabled = true,
        )
        assertFalse(withoutMfa.canBan)
        assertTrue(withMfa.canBan)
    }

    @Test fun `voice moderation is not elevation gated`() {
        val context = context(
            actingRoles = listOf(HIGH_ROLE), targetRoles = listOf(LOW_ROLE),
            permissions = Permission.MUTE_MEMBERS or Permission.DEAFEN_MEMBERS or
                Permission.MOVE_MEMBERS,
            mfaLevel = 1, mfaEnabled = false,
        )
        assertTrue(context.canMute)
        assertTrue(context.canDeafen)
        assertTrue(context.canMove)
    }

    @Test fun `changing your own nickname only needs CHANGE_NICKNAME`() {
        val self = context(
            actingRoles = emptyList(), targetRoles = emptyList(),
            permissions = Permission.CHANGE_NICKNAME, targetId = ACTOR,
        )
        val other = context(
            actingRoles = emptyList(), targetRoles = emptyList(),
            permissions = Permission.CHANGE_NICKNAME,
        )
        assertTrue(self.canChangeNickname)
        assertFalse(other.canChangeNickname)
    }

    @Test fun `the owner may manage anyone and may transfer ownership`() {
        val context = context(
            actingRoles = emptyList(), targetRoles = listOf(HIGH_ROLE),
            permissions = Permission.NONE, actingId = OWNER,
        )
        assertTrue(context.canKick)
        assertTrue(context.canBan)
        assertTrue(context.canManage())
    }

    @Test fun `no permissions means nothing to manage`() {
        val context = context(
            actingRoles = listOf(HIGH_ROLE), targetRoles = listOf(LOW_ROLE),
            permissions = Permission.NONE,
        )
        assertFalse(context.canManage())
    }

    private fun context(
        actingRoles: List<Long>,
        targetRoles: List<Long>,
        permissions: Long,
        actingId: Long = ACTOR,
        targetId: Long = TARGET,
        mfaLevel: Int = 0,
        mfaEnabled: Boolean = false,
    ) = ManageUserContext.from(
        guild = Guild(id = GUILD, ownerId = OWNER, mfaLevel = mfaLevel),
        actingUser = meUser(actingId, mfaEnabled),
        targetUser = CoreUser(id = targetId, username = "target"),
        actingUserRoles = actingRoles,
        userRoles = targetRoles,
        myPermissions = permissions,
        guildRoles = ROLES,
    )

    private fun meUser(id: Long, mfaEnabled: Boolean) = MeUser(
        id = id, username = "me", avatar = null, banner = null, isBot = false,
        isSystemUser = false, discriminator = 1, premiumTier = PremiumTier.NONE, email = null,
        mfaEnabled = mfaEnabled, isVerified = true, token = null, flags = 0, publicFlags = 0,
        phoneNumber = null, nsfwAllowance = NsfwAllowance.UNKNOWN, bio = null, bannerColor = null,
    )

    private companion object {
        const val GUILD = 1L
        const val ACTOR = 7L
        const val TARGET = 8L
        const val OWNER = 9L
        const val HIGH_ROLE = 20L
        const val LOW_ROLE = 21L

        val ROLES = mapOf(
            HIGH_ROLE to role(HIGH_ROLE, position = 10),
            LOW_ROLE to role(LOW_ROLE, position = 1),
        )

        fun role(id: Long, position: Int) =
            GuildRole(position, "role", id, 0, false, Permission.NONE, false, false, null, null, null)
    }
}
