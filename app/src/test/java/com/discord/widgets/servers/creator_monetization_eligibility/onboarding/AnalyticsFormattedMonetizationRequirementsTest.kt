package com.discord.widgets.servers.creator_monetization_eligibility.onboarding

import com.discord.api.creatormonetization.CreatorMonetizationEligibilityRequirements
import com.discord.api.creatormonetization.CreatorMonetizationEnableRequest
import com.discord.widgets.servers.creator_monetization_eligibility.onboarding.AnalyticsFormattedMonetizationRequirements.Companion.toAnalyticsFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsFormattedMonetizationRequirementsTest {

    @Test
    fun `analytics format preserves every eligibility signal`() {
        val request = CreatorMonetizationEnableRequest(7, CreatorMonetizationEnableRequest.State.OPEN)
        val formatted = CreatorMonetizationEligibilityRequirements(
            guildId = 1,
            latestRequest = request,
            canApply = true,
            sufficient = false,
            age = true,
            engagementHealthy = false,
            nsfwProperties = mapOf("explicit" to true),
            retentionHealthy = true,
            safeEnvironment = false,
            size = true,
            healthScore = CreatorMonetizationEligibilityRequirements.HealthScore(
                null, null, null, null, 123, 45, 0.67,
            ),
            healthScorePending = false,
            rejection = null,
            minimumAgeInDays = 30,
            minimumSize = 100,
        ).toAnalyticsFormat()

        assertFalse(formatted.isEligibleForMonetization)
        assertTrue(formatted.hasSufficientMembers)
        assertTrue(formatted.meetsServerAgeRequirement)
        assertFalse(formatted.noRecentViolations)
        assertFalse(formatted.weeklyCommunicators)
        assertTrue(formatted.hasMemberRetention)
        assertFalse(formatted.notNSFW)
        assertTrue(formatted.isApplicationPending)
        assertEquals(123, formatted.guildMemberCount)
        assertEquals(45, formatted.communicatorCount)
        assertEquals(0.67, formatted.retentionScore!!, 0.0)
        assertTrue(formatted.toJson().contains("\"minimumAgeInDays\":30"))
    }
}
