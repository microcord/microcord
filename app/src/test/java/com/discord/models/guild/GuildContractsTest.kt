package com.discord.models.guild

import com.discord.api.guild.GuildFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuildContractsTest {
    @Test
    fun derivesNamesAndPremiumFeatures() {
        assertEquals("MS", Guild(name = "Manti's Server").shortName)
        // Tierless boosting: reaching a level unlocks nothing on its own.
        assertFalse(Guild(premiumTier = 3).canHaveBanner())
        assertTrue(Guild(features = setOf(GuildFeature.BANNER)).canHaveBanner())
        assertTrue(Guild(features = setOf(GuildFeature.VANITY_URL)).canHaveVanityURL())
        // A guild that lost the perk keeps a vanity it had already set.
        assertTrue(
            Guild(features = setOf(GuildFeature.GUILD_WEB_PAGE_VANITY_URL), vanityUrlCode = "manti")
                .canHaveVanityURL(),
        )
        assertFalse(Guild(features = setOf(GuildFeature.GUILD_WEB_PAGE_VANITY_URL)).canHaveVanityURL())
        assertTrue(Guild(features = setOf(GuildFeature.HUB)).isHub())
    }
}
