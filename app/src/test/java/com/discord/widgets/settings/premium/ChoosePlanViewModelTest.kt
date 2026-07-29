package com.discord.widgets.settings.premium

import com.android.billingclient.api.SkuDetails
import com.discord.api.premium.SubscriptionInterval
import com.discord.utilities.billing.GooglePlaySku
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChoosePlanViewModelTest {
    @Test
    fun `tier two monthly rows preserve section order and skip absent details`() {
        val details = details(
            GooglePlaySku.PREMIUM_TIER_2_MONTHLY,
            GooglePlaySku.PREMIUM_TIER_2_PREMIUM_GUILD_1_MONTHLY,
        )

        val items = ChoosePlanViewModel.itemsForViewType(
            WidgetChoosePlan.ViewType.BUY_PREMIUM_TIER_2,
            oldSkuName = null,
            skuDetails = details,
        )

        assertEquals(
            listOf(
                GooglePlaySku.Section.PREMIUM.headerResource,
                GooglePlaySku.Section.PREMIUM_AND_PREMIUM_GUILD.headerResource,
            ),
            items.filterIsInstance<WidgetChoosePlanAdapter.Item.Header>().map { it.titleStringResId },
        )
        assertEquals(
            listOf(
                GooglePlaySku.PREMIUM_TIER_2_MONTHLY,
                GooglePlaySku.PREMIUM_TIER_2_PREMIUM_GUILD_1_MONTHLY,
            ),
            items.filterIsInstance<WidgetChoosePlanAdapter.Item.Plan>().map { it.displaySku },
        )
    }

    @Test
    fun `current plan offers its yearly upgrade when details exist`() {
        val current = GooglePlaySku.PREMIUM_TIER_2_MONTHLY
        val skuDetails = details(current, requireNotNull(current.upgrade))
        val items = ChoosePlanViewModel.currentPlanItems(
            current.skuName,
            skuDetails,
        )
        val plan = items.filterIsInstance<WidgetChoosePlanAdapter.Item.Plan>().single()

        assertTrue(plan.isCurrentPlan)
        assertEquals(current, plan.purchaseSku)
        assertSame(skuDetails[current.upgrade?.skuName], plan.upgradeSkuDetails)
    }

    @Test
    fun `current plan is display only when no valid change has details`() {
        val current = GooglePlaySku.PREMIUM_TIER_2_YEARLY
        val skuDetails = details(current)
        val plan = ChoosePlanViewModel.currentPlanItems(current.skuName, skuDetails)
            .filterIsInstance<WidgetChoosePlanAdapter.Item.Plan>()
            .single()

        assertTrue(plan.isCurrentPlan)
        assertNull(plan.purchaseSku)
        assertSame(skuDetails[current.skuName], plan.skuDetails)
    }

    @Test
    fun `boost picker only offers larger plans in current interval`() {
        val current = GooglePlaySku.PREMIUM_TIER_2_PREMIUM_GUILD_1_MONTHLY
        val larger = GooglePlaySku.PREMIUM_TIER_2_PREMIUM_GUILD_2_MONTHLY
        val yearly = GooglePlaySku.PREMIUM_TIER_2_PREMIUM_GUILD_2_YEARLY
        val rows = ChoosePlanViewModel.itemsForViewType(
            WidgetChoosePlan.ViewType.BUY_PREMIUM_GUILD,
            current.skuName,
            details(larger, yearly),
            SubscriptionInterval.YEARLY,
        ).filterIsInstance<WidgetChoosePlanAdapter.Item.Plan>()

        assertEquals(listOf(larger), rows.map { it.displaySku })
    }

    private fun details(vararg skus: GooglePlaySku): Map<String, SkuDetails> =
        skus.associate { sku ->
            sku.skuName to SkuDetails(
                """{"productId":"${sku.skuName}","type":"subs","title":"${sku.name}","description":"${sku.name}","price":"${'$'}9.99","price_amount_micros":9990000,"price_currency_code":"USD","subscriptionPeriod":"P1M"}""",
            )
        }
}
