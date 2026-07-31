package com.discord.widgets.settings.premium

import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.SkuDetails
import com.discord.api.premium.SubscriptionInterval
import com.discord.utilities.billing.GooglePlaySku
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONObject
import sun.misc.Unsafe

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

    @Test
    fun `subscription replacement preserves token account and reference proration`() {
        val old = details(GooglePlaySku.PREMIUM_TIER_2_MONTHLY).values.single()
        val upgrade = details(GooglePlaySku.PREMIUM_TIER_2_YEARLY).values.single()
        val downgrade = skuDetails("lower", 1_990_000)

        val upgradeParams = ChoosePlanViewModel.billingFlowParams(upgrade, old, "purchase-token", "account-hash")
        val downgradeParams = ChoosePlanViewModel.billingFlowParams(downgrade, old, "purchase-token", null)

        assertEquals(BillingFlowParams.ProrationMode.IMMEDIATE_WITH_TIME_PRORATION, hidden<Int>(upgradeParams, "zza"))
        assertEquals(BillingFlowParams.ProrationMode.DEFERRED, hidden<Int>(downgradeParams, "zza"))
        assertEquals("purchase-token", hidden<String>(upgradeParams, "zzd"))
        assertEquals("account-hash", hidden<String>(upgradeParams, "zzb"))
        assertSame(upgrade, hidden<ArrayList<SkuDetails>>(upgradeParams, "zzf").single())
        assertThrows(IllegalArgumentException::class.java) {
            ChoosePlanViewModel.billingFlowParams(upgrade, oldDetails = old)
        }
    }

    private fun details(vararg skus: GooglePlaySku): Map<String, SkuDetails> =
        skus.associate { sku ->
            sku.skuName to skuDetails(sku.skuName, 9_990_000)
        }

    private fun skuDetails(sku: String, priceAmountMicros: Long): SkuDetails =
        (unsafe.allocateInstance(SkuDetails::class.java) as SkuDetails).also { details ->
            SkuDetails::class.java.getDeclaredField("zza").apply { isAccessible = true }.set(details, sku)
            SkuDetails::class.java.getDeclaredField("zzb").apply { isAccessible = true }
                .set(details, object : JSONObject() {
                    override fun optString(name: String?): String = when (name) {
                        "productId" -> sku
                        "type" -> "subs"
                        "title", "description" -> sku
                        else -> ""
                    }

                    override fun optLong(name: String?): Long =
                        if (name == "price_amount_micros") priceAmountMicros else 0
                })
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> hidden(params: BillingFlowParams, method: String): T =
        params.javaClass.getDeclaredMethod(method).invoke(params) as T

    private companion object {
        val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }
    }
}
