package com.discord.utilities.billing

import com.android.billingclient.api.Purchase
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreGooglePlayPurchases
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn

object BillingUtils {
    val paymentFlowAnalytics = object : StoreGooglePlayPurchases.PaymentFlowAnalytics {
        override fun paymentFlowStarted(trait: StoreGooglePlayPurchases.AnalyticsTrait) =
            track("payment_flow_started", trait)

        override fun paymentFlowStep(
            trait: StoreGooglePlayPurchases.AnalyticsTrait,
            fromStep: String,
            toStep: String,
        ) = track("payment_flow_step", trait, mapOf("from_step" to fromStep, "to_step" to toStep))

        override fun paymentFlowCompleted(trait: StoreGooglePlayPurchases.AnalyticsTrait) =
            track("payment_flow_completed", trait)

        override fun paymentFlowFailed(trait: StoreGooglePlayPurchases.AnalyticsTrait) =
            track("payment_flow_failed", trait)
    }

    fun createPendingPurchaseMetadata(
        paymentGatewaySkuId: String,
        skuId: Long,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    ) {
        val api = RestAPI.apiOrNull ?: return onFailure()
        api.createPurchaseMetadata(RestAPIParams.PurchaseMetadataBody(skuId, paymentGatewaySkuId))
            .restSubscribeOn()
            .appSubscribe(BillingUtils::class.java, errorHandler = { onFailure() }) { onSuccess() }
    }

    fun microToMinor(amount: Long): Long = amount / 10_000L

    fun verifyPurchases(purchases: List<Purchase>?) {
        purchases.orEmpty().filter(::shouldVerifyPurchase).forEach { purchase ->
            purchase.skus.forEach { verifyPurchase(purchase, it) }
        }
    }

    internal fun shouldVerifyPurchase(purchase: Purchase): Boolean =
        purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged

    internal fun verificationBody(purchase: Purchase, sku: String): RestAPIParams.VerifyPurchaseTokenBody {
        val isInApp = GooglePlayInAppSkus.isInAppSku(sku)
        return RestAPIParams.VerifyPurchaseTokenBody(
            purchaseToken = purchase.purchaseToken,
            userId = StoreStream.getUsers().getMe().id,
            packageName = purchase.packageName,
            subscriptionSkuId = sku.takeUnless { isInApp },
            oneTimePurchaseSkuId = sku.takeIf { isInApp },
        )
    }

    private fun verifyPurchase(purchase: Purchase, sku: String) {
        val store = StoreStream.getGooglePlayPurchases()
        val api = RestAPI.apiOrNull
        store.onVerificationStart()
        if (api == null) {
            store.onVerificationFailure(StoreGooglePlayPurchases.VerificationResult(purchase, null))
            store.trackPaymentFlowFailed(sku)
            return
        }
        api.verifyPurchaseToken(verificationBody(purchase, sku))
            .restSubscribeOn()
            .appSubscribe(
                BillingUtils::class.java,
                errorHandler = {
                    store.onVerificationFailure(StoreGooglePlayPurchases.VerificationResult(purchase, null))
                    store.trackPaymentFlowFailed(sku)
                },
            ) { response ->
                store.onVerificationSuccess(StoreGooglePlayPurchases.VerificationResult(purchase, response))
                GooglePlayInAppSkus.getInAppSku(sku)?.let {
                    GooglePlayBillingManager.consumePurchase(purchase, it.type, response.verifiedSkuId)
                }
            }
    }

    private fun track(
        event: String,
        trait: StoreGooglePlayPurchases.AnalyticsTrait,
        extra: Map<String, Any> = emptyMap(),
    ) {
        val properties = extra.toMutableMap()
        trait.locationTrait.serializeTo(properties)
        trait.storeSkuTrait.serializeTo(properties)
        trait.paymentTrait.serializeTo(properties)
        AnalyticsUtils.Tracker.instance.track(event, properties)
    }
}
