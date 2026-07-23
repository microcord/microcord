package com.discord.views.guildboost

import com.discord.api.guild.GuildFeature
import com.discord.models.guild.Guild
import org.junit.Assert.assertEquals
import org.junit.Test

class GuildBoostViewsTest {
    @Test
    fun calculatesReferenceBoostTiers() {
        val standard = Guild()
        assertEquals(0, GuildBoostConfirmationView.boostTier(standard, 1))
        assertEquals(1, GuildBoostConfirmationView.boostTier(standard, 2))
        assertEquals(2, GuildBoostConfirmationView.boostTier(standard, 7))
        assertEquals(3, GuildBoostConfirmationView.boostTier(standard, 14))

        val small = Guild(features = setOf(GuildFeature.BOOSTING_TIERS_EXPERIMENT_SMALL_GUILD))
        assertEquals(2, GuildBoostConfirmationView.boostTier(small, 3))
        assertEquals(3, GuildBoostConfirmationView.boostTier(small, 4))

        val medium = Guild(features = setOf(GuildFeature.BOOSTING_TIERS_EXPERIMENT_MEDIUM_GUILD))
        assertEquals(2, GuildBoostConfirmationView.boostTier(medium, 7))
        assertEquals(3, GuildBoostConfirmationView.boostTier(medium, 10))
    }
}
