package com.discord.utilities.user

import com.discord.api.premium.PremiumTier
import com.discord.api.presence.ClientStatus
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.presence.Presence
import com.discord.models.user.CoreUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserUtilsTest {

    @Test fun `badges read the union of flags and public flags`() = with(UserUtils) {
        assertTrue(user(flags = 1).isStaff)
        assertTrue(user(publicFlags = 1).isStaff)
        assertFalse(user().isStaff)
        assertTrue(user(publicFlags = 2).isPartner)
        assertTrue(user(publicFlags = 8).isBugHunterLevel1)
        assertTrue(user(publicFlags = 16384).isBugHunterLevel2)
        assertTrue(user(publicFlags = 262144).isCertifiedModerator)
        assertTrue(user(publicFlags = 131072).isVerifiedDeveloper)
        assertTrue(user(flags = 8192).hasUnreadUrgentMessages)
    }

    @Test fun `hypesquad house membership`() = with(UserUtils) {
        assertTrue(user(publicFlags = 64).isHypesquadHouse1)
        assertTrue(user(publicFlags = 128).isHypesquadHouse2)
        assertTrue(user(publicFlags = 256).isHypesquadHouse3)
        assertTrue(user(publicFlags = 128).isInHypesquadHouse)
        assertFalse(user(publicFlags = 4).isInHypesquadHouse)
        assertTrue(user(publicFlags = 4).isHypeSquad)
    }

    @Test fun `verified bot covers the flag the system account and clyde`() = with(UserUtils) {
        assertTrue(user(publicFlags = 65536).isVerifiedBot)
        assertTrue(user(isSystemUser = true).isVerifiedBot)
        assertTrue(user(id = UserUtils.CLYDE_BOT_USER_ID).isVerifiedBot)
        assertFalse(user().isVerifiedBot)
    }

    @Test fun `premium excludes the guild-boost-only pseudo tier`() = with(UserUtils) {
        assertFalse(user(premiumTier = PremiumTier.NONE).isPremium)
        assertFalse(user(premiumTier = PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY).isPremium)
        assertTrue(user(premiumTier = PremiumTier.TIER_2).isPremium)
        assertTrue(user(premiumTier = PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY).hasSubscription)
        assertTrue(user(premiumTier = PremiumTier.TIER_2).canUsePremiumStickers)
        assertTrue(user(premiumTier = PremiumTier.TIER_0).canUsePremiumStickers)
        assertFalse(user(premiumTier = PremiumTier.TIER_1).canUsePremiumStickers)
    }

    @Test fun `the discriminator retires on migration, not on being human`() = with(UserUtils) {
        assertEquals("#0001", padDiscriminator(1))
        assertEquals("#1234", padDiscriminator(1234))

        val migrated = user(username = "ada", discriminator = 0)
        assertTrue(migrated.hasUniqueUsername)
        assertEquals("", migrated.discriminatorWithPadding)
        assertEquals("ada", migrated.getUserNameWithDiscriminator())

        // A bot keeps its discriminator, and so would a human who had not migrated.
        val bot = user(username = "ada", discriminator = 42, isBot = true)
        assertFalse(bot.hasUniqueUsername)
        assertEquals("#0042", bot.discriminatorWithPadding)
        assertEquals("ada#0042", bot.getUserNameWithDiscriminator())
        assertEquals("#0042", user(username = "ada", discriminator = 42).discriminatorWithPadding)
    }

    @Test fun `display name prefers nickname then global name then username`() = with(UserUtils) {
        val user = user(username = "handle", globalName = "Display")
        assertEquals("Display", user.displayName)
        assertEquals("Guild Nick", user.getDisplayName("Guild Nick"))
        assertEquals("Display", user.getDisplayName("   "))
        assertEquals("handle", user(username = "handle", globalName = " ").displayName)
    }

    @Test fun `name comparison prefers nicknames and falls back to id`() = with(UserUtils) {
        val ada = user(id = 1, username = "ada")
        val grace = user(id = 2, username = "grace")
        assertTrue(compareUserNames(ada, grace) < 0)
        assertTrue(compareUserNames(user(id = 1, username = "zoe", globalName = "Ada"), grace) < 0)
        // A nickname replaces the username for ordering.
        assertTrue(compareUserNames(ada, grace, "zoe", null) > 0)
        // A blank nickname is ignored.
        assertTrue(compareUserNames(ada, grace, "   ", null) < 0)
        // Identical names order by id.
        assertTrue(compareUserNames(user(id = 1, username = "same"), user(id = 2, username = "same")) < 0)
        assertEquals(0, compareUserNames(null, null))
        assertTrue(compareUserNames(null, grace) < 0)
        assertTrue(compareUserNames(ada, null) > 0)
    }

    @Test fun `account age is derived from the snowflake`() = with(UserUtils) {
        // Snowflake 0 is the Discord epoch.
        val epochUser = user(id = 0)
        assertEquals(1_000L, epochUser.getAgeMs { DISCORD_EPOCH + 1_000L })
        assertFalse(epochUser.isAccountOldEnough { DISCORD_EPOCH + 1_000L })
        assertTrue(epochUser.isAccountOldEnough { DISCORD_EPOCH + 600_000L })
        assertTrue(epochUser.isNewUser { DISCORD_EPOCH + 1_000L })
        assertFalse(epochUser.isNewUser { DISCORD_EPOCH + 700_000_000L })
        assertTrue(epochUser.since(UtcDateTime(DISCORD_EPOCH + 1)))
        assertFalse(epochUser.since(UtcDateTime(DISCORD_EPOCH - 1)))
    }

    @Test fun `status visibility hides interaction bots without a live presence`() {
        val bot = user(publicFlags = 524288)
        val human = user()
        val online = Presence(ClientStatus.ONLINE, null, null)
        val offline = Presence(ClientStatus.OFFLINE, null, null)

        assertTrue(UserUtils.isStatusVisible(bot, online, false))
        assertFalse(UserUtils.isStatusVisible(bot, offline, true))
        assertFalse(UserUtils.isStatusVisible(bot, null, true))
        assertTrue(UserUtils.isStatusVisible(human, null, true))
        assertFalse(UserUtils.isStatusVisible(human, null, false))
        assertTrue(UserUtils.isStatusVisible(human, offline, false))
    }

    @Test fun `obfuscated user id is a stable sha-256 hex digest`() {
        val hash = UserUtils.getObfuscatedUserId(7)
        assertEquals(64, hash?.length)
        assertEquals(hash, UserUtils.getObfuscatedUserId(7))
        assertNull(UserUtils.getObfuscatedUserId(null))
    }

    @Test fun `system user id is the documented account`() {
        assertEquals(643945264868098049L, UserUtils.SYSTEM_USER_ID)
    }

    private fun user(
        id: Long = 100,
        username: String = "user",
        discriminator: Int = 0,
        flags: Int = 0,
        publicFlags: Int = 0,
        isSystemUser: Boolean = false,
        isBot: Boolean = false,
        globalName: String? = null,
        premiumTier: PremiumTier = PremiumTier.NONE,
    ) = CoreUser(
        id = id, username = username, isSystemUser = isSystemUser, discriminator = discriminator,
        premiumTier = premiumTier, flags = flags, publicFlags = publicFlags, isBot = isBot,
        globalName = globalName,
    )

    private companion object {
        const val DISCORD_EPOCH = 1_420_070_400_000L
    }
}
