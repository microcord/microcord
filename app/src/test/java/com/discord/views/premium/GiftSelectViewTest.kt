package com.discord.views.premium

import com.discord.R
import com.discord.utilities.billing.InAppSkuType
import com.discord.utilities.billing.GooglePlayInAppSku
import com.discord.utilities.billing.premiumTier1Month
import com.discord.utilities.billing.premiumTier1Year
import com.discord.utilities.billing.premiumTier2Month
import com.discord.utilities.billing.premiumTier2Year
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GiftSelectViewTest {
    @Test
    fun mapsGiftPlansAndAccessibilityLabel() {
        assertEquals(R.drawable.ic_plan_premium_tier_1, GiftSelectView.iconFor(premiumTier1Month))
        assertEquals(R.drawable.ic_plan_premium_tier_1_year, GiftSelectView.iconFor(premiumTier1Year))
        assertEquals(R.drawable.ic_plan_premium_tier_2, GiftSelectView.iconFor(premiumTier2Month))
        assertEquals(R.drawable.ic_plan_premium_tier_2_year, GiftSelectView.iconFor(premiumTier2Year))
        assertNull(GiftSelectView.iconFor(GooglePlayInAppSku("unknown", InAppSkuType.PREMIUM_GIFT)))
        assertEquals("Nitro, $9.99", GiftSelectView.accessibilityLabel("Nitro", "$9.99"))
    }
}
