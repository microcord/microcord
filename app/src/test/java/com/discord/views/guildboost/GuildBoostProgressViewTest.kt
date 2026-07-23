package com.discord.views.guildboost

import org.junit.Assert.assertEquals
import org.junit.Test

class GuildBoostProgressViewTest {
    @Test
    fun calculatesReferenceProgressForEachTier() {
        assertEquals(0, GuildBoostProgressView.percentToNextTier(0, 0))
        assertEquals(50, GuildBoostProgressView.percentToNextTier(0, 1))
        assertEquals(57, GuildBoostProgressView.percentToNextTier(1, 6))
        assertEquals(43, GuildBoostProgressView.percentToNextTier(2, 13))
        assertEquals(100, GuildBoostProgressView.percentToNextTier(3, 14))
    }
}
