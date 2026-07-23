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
        assertTrue(Guild(premiumTier = 2).canHaveBanner())
        assertFalse(Guild(premiumTier = 2).canHaveVanityURL())
        assertTrue(Guild(features = setOf(GuildFeature.HUB)).isHub())
    }
}
