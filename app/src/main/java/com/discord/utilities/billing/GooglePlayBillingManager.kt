package com.discord.utilities.billing

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.SkuDetails
import com.android.billingclient.api.SkuDetailsParams
import com.android.billingclient.api.SkuDetailsResponseListener
import com.discord.app.AppActivity
import com.discord.app.AppLog
import com.discord.stores.StoreStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import rx.Observable

object GooglePlayBillingManager :
    PurchasesUpdatedListener,
    BillingClientStateListener,
    SkuDetailsResponseListener,
    ConsumeResponseListener {

    const val PLAY_STORE_SUBSCRIPTION_DEEPLINK_URL =
        "https://play.google.com/store/account/subscriptions?sku=%s&package=%s"
    const val PLAY_STORE_SUBSCRIPTION_URL = "https://play.google.com/store/account/subscriptions"

    private const val DEFAULT_BACKOFF_TIME_MS = 1_000L
    private const val CLOSED_CONNECTION_STATE = 3
    private val backoffTimeMs = AtomicLong(DEFAULT_BACKOFF_TIME_MS)
    private val activeActivities = AtomicInteger()
    private val inAppSkusToConsume = HashMap<String, InAppSkuToConsume>()

    @Volatile private var billingClient: BillingClient? = null
    @Volatile private var isAuthenticated = false
    private lateinit var application: Application

    data class InAppSkuToConsume(
        val type: InAppSkuType,
        val skuId: Long?,
        val paymentGatewaySkuId: String,
    )

    internal data class PurchaseGroups(
        val subscriptions: List<Purchase>,
        val inApp: List<Purchase>,
    )

    @Synchronized
    fun init(application: Application) {
        if (::application.isInitialized) return
        this.application = application
        billingClient = newBillingClient()
        application.registerActivityLifecycleCallbacks(LifecycleListener())
        StoreStream.getAuthentication().observeIsAuthed().subscribe { authenticated ->
            isAuthenticated = authenticated
            if (authenticated && activeActivities.get() > 0) connect()
        }
    }

    fun launchBillingFlow(activity: Activity, params: BillingFlowParams): Int {
        val client = billingClient
        return if (client?.isReady == true) {
            client.launchBillingFlow(activity, params).responseCode
        } else {
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED
        }
    }

    fun queryPurchases() {
        val client = billingClient?.takeIf(BillingClient::isReady) ?: return
        client.queryPurchasesAsync(BillingClient.SkuType.SUBS, PurchasesResponseListener { _, purchases ->
            StoreStream.getGooglePlayPurchases().processPurchases(purchases, BillingClient.SkuType.SUBS)
        })
        client.queryPurchasesAsync(BillingClient.SkuType.INAPP, PurchasesResponseListener { _, purchases ->
            StoreStream.getGooglePlayPurchases().processPurchases(purchases, BillingClient.SkuType.INAPP)
        })
    }

    fun querySkuDetails() {
        querySkuDetails(BillingClient.SkuType.SUBS, GooglePlaySku.ALL_SKU_NAMES)
    }

    fun consumePurchase(purchase: Purchase, inAppSkuType: InAppSkuType, skuId: Long?) {
        val client = billingClient?.takeIf(BillingClient::isReady) ?: return
        val token = purchase.purchaseToken.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Purchase token must be set")
        synchronized(inAppSkusToConsume) {
            purchase.skus.forEach { inAppSkusToConsume[token] = InAppSkuToConsume(inAppSkuType, skuId, it) }
        }
        client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(token).build(), this)
    }

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) return
        querySkuDetails()
        queryInAppSkuDetails()
        queryPurchases()
        backoffTimeMs.set(DEFAULT_BACKOFF_TIME_MS)
    }

    override fun onBillingServiceDisconnected() {
        if (!isAuthenticated || activeActivities.get() == 0) return
        val delay = backoffTimeMs.get()
        Observable.timer(delay, TimeUnit.MILLISECONDS).subscribe {
            if (billingClient?.isReady != true) {
                connect()
                backoffTimeMs.compareAndSet(delay, nextBackoff(delay))
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            StoreStream.getGooglePlayPurchases().updatePendingDowngrade(null)
            when (billingResult.responseCode) {
                BillingClient.BillingResponseCode.USER_CANCELED ->
                    AppLog.i("onPurchasesUpdated: User canceled the purchase")
                BillingClient.BillingResponseCode.DEVELOPER_ERROR -> AppLog.e(
                    "onPurchasesUpdated: Play does not recognize this app/SKU configuration.",
                )
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                    AppLog.i("onPurchasesUpdated: The user already owns this item")
            }
            return
        }

        if (purchases.isNullOrEmpty()) StoreStream.getGooglePlayPurchases().downgradePurchase()
        val groups = partitionPurchases(purchases.orEmpty())
        if (groups.inApp.isNotEmpty()) {
            StoreStream.getGooglePlayPurchases().processPurchases(groups.inApp, BillingClient.SkuType.INAPP)
        }
        if (groups.subscriptions.isNotEmpty()) {
            StoreStream.getGooglePlayPurchases().processPurchases(groups.subscriptions, BillingClient.SkuType.SUBS)
        }
    }

    override fun onSkuDetailsResponse(billingResult: BillingResult, skuDetails: List<SkuDetails>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            StoreStream.getGooglePlaySkuDetails().updateSkuDetails(skuDetails.orEmpty())
        } else {
            StoreStream.getGooglePlaySkuDetails().handleError()
            if (billingResult.responseCode in setOf(
                    BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
                    BillingClient.BillingResponseCode.USER_CANCELED,
                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED,
                    BillingClient.BillingResponseCode.ITEM_NOT_OWNED,
                )
            ) {
                AppLog.e("onSkuDetailsResponse: ${billingResult.responseCode} ${billingResult.debugMessage}")
            }
        }
    }

    override fun onConsumeResponse(billingResult: BillingResult, purchaseToken: String) {
        val consumed = synchronized(inAppSkusToConsume) { inAppSkusToConsume.remove(purchaseToken) } ?: return
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            StoreStream.getGooglePlayPurchases().trackPaymentFlowCompleted(consumed.paymentGatewaySkuId)
        } else {
            StoreStream.getGooglePlayPurchases().trackPaymentFlowFailed(consumed.paymentGatewaySkuId)
            AppLog.e("Failed to consume purchase. Billing response code: ${billingResult.responseCode}")
        }
    }

    @Synchronized
    private fun connect() {
        if (!::application.isInitialized) return
        var client = billingClient
        if (client == null || client.connectionState == CLOSED_CONNECTION_STATE) {
            client = newBillingClient()
            billingClient = client
        }
        if (!client.isReady && client.connectionState != BillingClient.ConnectionState.CONNECTING) {
            client.startConnection(this)
        }
    }

    private fun newBillingClient(): BillingClient = BillingClient.newBuilder(application.applicationContext)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    private fun querySkuDetails(type: String, skus: List<String>) {
        if (skus.isEmpty()) return
        val client = billingClient?.takeIf(BillingClient::isReady) ?: return
        client.querySkuDetailsAsync(
            SkuDetailsParams.newBuilder().setType(type).setSkusList(skus).build(),
            this,
        )
    }

    private fun queryInAppSkuDetails() = querySkuDetails(
        BillingClient.SkuType.INAPP,
        GooglePlayInAppSkus.skus.map { it.paymentGatewaySkuId },
    )

    private fun onActivityCreated() {
        activeActivities.incrementAndGet()
        if (isAuthenticated) connect()
    }

    private fun onActivityDestroyed() {
        val remaining = activeActivities.decrementAndGet().coerceAtLeast(0)
        activeActivities.set(remaining)
        if (remaining == 0 && billingClient?.isReady == true) {
            billingClient?.endConnection()
        }
    }

    private class LifecycleListener : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is AppActivity) GooglePlayBillingManager.onActivityCreated()
        }

        override fun onActivityResumed(activity: Activity) {
            if (activity is AppActivity && GooglePlayBillingManager.isAuthenticated) {
                GooglePlayBillingManager.queryPurchases()
            }
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (activity is AppActivity) GooglePlayBillingManager.onActivityDestroyed()
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    internal fun partitionPurchases(purchases: List<Purchase>): PurchaseGroups {
        val (inApp, subscriptions) = purchases.partition { purchase ->
            purchase.skus.any(GooglePlayInAppSkus::isInAppSku)
        }
        return PurchaseGroups(subscriptions, inApp)
    }

    internal fun nextBackoff(current: Long): Long =
        if (current > Long.MAX_VALUE / 2L) Long.MAX_VALUE else current * 2L
}
