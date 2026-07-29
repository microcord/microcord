package com.discord.widgets.settings.premium

import com.discord.api.premium.SubscriptionInterval
import com.discord.utilities.billing.GooglePlaySku
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GooglePlaySkuTest {
    @Test
    fun `catalog preserves 126 21 order and relationships`() {
        assertEquals(19, GooglePlaySku.entries.size)
        assertEquals(GooglePlaySku.PREMIUM_TIER_2_YEARLY, GooglePlaySku.entries.first())
        assertEquals(GooglePlaySku.PREMIUM_GUILD_2_MONTHLY, GooglePlaySku.entries.last())
        assertEquals(
            GooglePlaySku.PREMIUM_TIER_2_YEARLY,
            GooglePlaySku.PREMIUM_TIER_2_MONTHLY.upgrade,
        )
        assertEquals(
            GooglePlaySku.PREMIUM_TIER_2_MONTHLY,
            GooglePlaySku.getDowngrade(GooglePlaySku.PREMIUM_TIER_2_YEARLY.skuName),
        )
        assertEquals(SubscriptionInterval.MONTHLY, GooglePlaySku.PREMIUM_GUILD_2_MONTHLY.interval)
        assertTrue(GooglePlaySku.PREMIUM_TIER_2_PREMIUM_GUILD_13_MONTHLY.isBundledSku)
    }

    @Test
    fun `unknown product id is never invented`() {
        assertNull(GooglePlaySku.fromSkuName("manticord_product_not_configured"))
    }
}
