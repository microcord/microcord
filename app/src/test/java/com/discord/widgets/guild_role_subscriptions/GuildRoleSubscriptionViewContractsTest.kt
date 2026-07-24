package com.discord.widgets.guild_role_subscriptions

import com.discord.widgets.servers.creator_monetization_eligibility.onboarding.AcceptTermsStepCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuildRoleSubscriptionViewContractsTest {
    @Test
    fun usesReferenceAssetUrlAndTermsLinkContracts() {
        assertEquals(
            "https://cdn.discordapp.com/app-assets/12/store/34.jpg?size=100",
            GuildSubscriptionRoleImageUploadView.storeAssetImage(12, 34, 100),
        )
        assertNull(GuildSubscriptionRoleImageUploadView.storeAssetImage(null, 34, 100))

        val (text, range, url) = AcceptTermsStepCard.parseMarkdownLink(
            "[Check out the terms](https://discord.com/terms), then continue.",
        )
        assertEquals("Check out the terms, then continue.", text)
        assertEquals(0..18, range)
        assertEquals("https://discord.com/terms", url)
    }
}
