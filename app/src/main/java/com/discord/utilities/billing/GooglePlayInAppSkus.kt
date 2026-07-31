package com.discord.utilities.billing

import com.android.billingclient.api.SkuDetails
import com.discord.models.domain.ModelSku

object GooglePlayInAppSkus {
    val skus: List<GooglePlayInAppSku> = premiumGifts

    private val skusById: Map<String, GooglePlayInAppSku>
        get() = skus.associateBy(GooglePlayInAppSku::paymentGatewaySkuId)

    fun getInAppSku(paymentGatewaySkuId: String): GooglePlayInAppSku? = skusById[paymentGatewaySkuId]

    fun getInAppSku(sku: ModelSku): GooglePlayInAppSku? =
        premiumGifts.firstOrNull { it.paymentGatewaySkuId == sku.name }

    fun isInAppSku(paymentGatewaySkuId: String): Boolean = paymentGatewaySkuId in skusById

    fun populateSkuDetails(skuDetails: Map<String, SkuDetails>) {
        skuDetails.forEach { (id, details) -> skusById[id]?.skuDetails = details }
    }
}
