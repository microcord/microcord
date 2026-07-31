package com.discord.utilities.billing

import com.android.billingclient.api.Purchase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GooglePlayBillingRuntimeTest {
    @After
    fun clearSkuConfiguration() = InAppSkuConfig.clear()

    @Test
    fun `purchase routing separates gifts from subscriptions`() {
        val gift = purchase("premium_month_tier_1")
        val subscription = purchase("premium_tier_2_monthly")

        val groups = GooglePlayBillingManager.partitionPurchases(listOf(subscription, gift))

        assertEquals(listOf(gift), groups.inApp)
        assertEquals(listOf(subscription), groups.subscriptions)
    }

    @Test
    fun `verification accepts only purchased unacknowledged records`() {
        assertTrue(BillingUtils.shouldVerifyPurchase(purchase("sku")))
        assertFalse(BillingUtils.shouldVerifyPurchase(purchase("sku", acknowledged = true)))
        assertFalse(BillingUtils.shouldVerifyPurchase(purchase("sku", purchaseState = 4)))
    }

    @Test
    fun `Manticord SKU configuration installs atomically and clears`() {
        val ids = premiumGifts.mapIndexed { index, sku -> sku.paymentGatewaySkuId to (index + 1L) }.toMap()

        InAppSkuConfig.installSkuIds(ids)

        assertTrue(InAppSkuConfig.isInstalled)
        premiumGifts.forEach { assertEquals(ids[it.paymentGatewaySkuId], it.skuId) }
        InAppSkuConfig.clear()
        assertFalse(InAppSkuConfig.isInstalled)
        premiumGifts.forEach { assertNull(it.skuId) }
    }

    @Test
    fun `billing helpers preserve reference arithmetic and backoff`() {
        assertEquals(123L, BillingUtils.microToMinor(1_239_999L))
        assertEquals(2_000L, GooglePlayBillingManager.nextBackoff(1_000L))
        assertEquals(Long.MAX_VALUE, GooglePlayBillingManager.nextBackoff(Long.MAX_VALUE))
    }

    @Test
    fun `reference billing JVM paths exist`() {
        listOf(
            "BillingUtils",
            "GooglePlayBillingManager",
            "GooglePlayInAppSkuKt",
            "GooglePlayInAppSkus",
            "GooglePlaySkuKt",
            "InAppSkuType",
            "PremiumUtilsKt",
        ).forEach { Class.forName("com.discord.utilities.billing.$it") }
    }

    private fun purchase(
        sku: String,
        acknowledged: Boolean = false,
        purchaseState: Int = 0,
    ) = object : Purchase("{}", "signature") {
        override fun getSkus() = arrayListOf(sku)

        override fun getPurchaseState() =
            if (purchaseState == 4) Purchase.PurchaseState.PENDING else Purchase.PurchaseState.PURCHASED

        override fun isAcknowledged() = acknowledged
    }
}
