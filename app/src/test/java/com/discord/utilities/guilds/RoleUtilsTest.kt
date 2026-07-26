package com.discord.utilities.guilds

import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.api.role.GuildRoleTags
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.member.GuildMember
import com.discord.nullserializable.NullSerializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleUtilsTest {

    @Test fun `higher position outranks lower position`() {
        val high = role(1, position = 5)
        val low = role(2, position = 1)
        assertTrue(RoleUtils.rankIsHigher(high, low))
        assertFalse(RoleUtils.rankIsHigher(low, high))
        assertFalse(RoleUtils.rankEquals(high, low))
    }

    @Test fun `equal positions are broken by the older role`() {
        val older = role(10, position = 3)
        val newer = role(20, position = 3)
        assertTrue(RoleUtils.rankIsHigher(older, newer))
        assertFalse(RoleUtils.rankIsHigher(newer, older))
    }

    @Test fun `identical roles rank equal`() {
        assertTrue(RoleUtils.rankEquals(role(1, position = 3), role(1, position = 3)))
        assertTrue(RoleUtils.rankEquals(null, null))
    }

    @Test fun `any role outranks no role`() {
        assertTrue(RoleUtils.rankIsHigher(role(1, position = 0), null))
        assertFalse(RoleUtils.rankIsHigher(null, role(1, position = 0)))
    }

    @Test fun `highest role ignores roles the guild does not know`() {
        val roles = mapOf(1L to role(1, position = 1), 2L to role(2, position = 7))
        assertEquals(2L, RoleUtils.getHighestRole(roles, listOf(1L, 2L, 99L))?.id)
        assertNull(RoleUtils.getHighestRole(roles, listOf(99L)))
        assertNull(RoleUtils.getHighestRole(roles, null as Collection<Long>?))
        assertNull(RoleUtils.getHighestRole(roles, null as GuildMember?))
    }

    @Test fun `highest hoisted role skips non-hoisted roles`() {
        val roles = mapOf(
            1L to role(1, position = 9, hoist = false),
            2L to role(2, position = 4, hoist = true),
            3L to role(3, position = 2, hoist = true),
        )
        val member = member(1, 2, 3)
        assertEquals(1L, RoleUtils.getHighestRole(roles, member)?.id)
        assertEquals(2L, RoleUtils.getHighestHoistedRole(roles, member)?.id)
        assertNull(RoleUtils.getHighestHoistedRole(roles, member(1)))
    }

    @Test fun `default colour is the zero sentinel`() {
        assertTrue(RoleUtils.isDefaultColor(role(1, color = 0)))
        assertFalse(RoleUtils.isDefaultColor(role(1, color = 0x5865F2)))
    }

    @Test fun `opaque colour forces full alpha and falls back to the default`() {
        assertEquals(0xFF5865F2.toInt(), RoleUtils.getOpaqueColor(role(1, color = 0x5865F2)))
        assertEquals(0xFF000000.toInt(), RoleUtils.getOpaqueColor(role(1, color = 0)))
        assertEquals(0xFF112233.toInt(), RoleUtils.getOpaqueColor(null, 0x112233))
    }

    @Test fun `role mention detection`() {
        assertTrue(RoleUtils.containsRoleMentions("hey <@&12345> look"))
        assertFalse(RoleUtils.containsRoleMentions("hey <@12345> look"))
        assertFalse(RoleUtils.containsRoleMentions(null))
    }

    @Test fun `subscription roles need both a listing and availability`() {
        val listed = role(1, tags = tags(listingId = "42", purchasable = true))
        val listedUnavailable = role(2, tags = tags(listingId = "42", purchasable = false))
        val unlisted = role(3, tags = tags(listingId = null, purchasable = true))

        assertTrue(RoleUtils.hasSubscriptionListingId(listed))
        assertFalse(RoleUtils.hasSubscriptionListingId(unlisted))
        assertTrue(RoleUtils.isSubscriptionRolePurchasableOrHasSubscribers(listed))
        assertFalse(RoleUtils.isSubscriptionRolePurchasableOrHasSubscribers(listedUnavailable))
        assertFalse(RoleUtils.isSubscriptionRolePurchasableOrHasSubscribers(unlisted))
        assertFalse(RoleUtils.isSubscriptionRolePurchasableOrHasSubscribers(role(4)))
    }

    private fun tags(listingId: String?, purchasable: Boolean) = GuildRoleTags(
        premiumSubscriber = null,
        botId = null,
        integrationId = null,
        skuId = null,
        subscriptionListingId = listingId,
        purchasableOrHasSubscribers = if (purchasable) NullSerializable.Value(Any()) else null,
    )

    private fun role(
        id: Long,
        position: Int = 0,
        color: Int = 0,
        hoist: Boolean = false,
        permissions: Long = Permission.NONE,
        tags: GuildRoleTags? = null,
    ) = GuildRole(position, "role", id, color, hoist, permissions, false, false, null, tags, null)

    private fun member(vararg roles: Long) = GuildMember(
        color = 0, hoistRoleId = 0, roles = roles.toList(), nick = null, premiumSince = null,
        pending = false, joinedAt = UtcDateTime(0), guildId = 1, userId = 7,
    )
}
