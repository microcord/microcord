package com.discord.widgets.settings.premium

import com.discord.utilities.billing.GooglePlaySku
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetChoosePlanActivationTest {
    @Test
    fun `every product type reaches its reference confirmation dialog`() {
        val expected = mapOf(
            GooglePlaySku.Type.PREMIUM_TIER_2 to WidgetChoosePlan.ActivationDialog.PREMIUM_TIER_2,
            GooglePlaySku.Type.PREMIUM_TIER_1 to WidgetChoosePlan.ActivationDialog.PREMIUM_TIER_1,
            GooglePlaySku.Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD to
                WidgetChoosePlan.ActivationDialog.PREMIUM_AND_GUILD_BOOST,
            GooglePlaySku.Type.PREMIUM_TIER_1_AND_PREMIUM_GUILD to
                WidgetChoosePlan.ActivationDialog.PREMIUM_AND_GUILD_BOOST,
            GooglePlaySku.Type.PREMIUM_GUILD to WidgetChoosePlan.ActivationDialog.GUILD_BOOST,
        )

        assertEquals(expected, GooglePlaySku.Type.entries.associateWith(WidgetChoosePlan::activationDialog))
    }
}
