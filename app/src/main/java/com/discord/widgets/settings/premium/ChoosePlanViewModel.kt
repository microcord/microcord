package com.discord.widgets.settings.premium

import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.SkuDetails
import com.discord.R
import com.discord.api.premium.SubscriptionInterval
import com.discord.app.AppLog
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelSubscription
import com.discord.stores.PendingDowngrade
import com.discord.stores.StoreGooglePlayPurchases
import com.discord.stores.StoreGooglePlaySkuDetails
import com.discord.stores.StoreStream
import com.discord.stores.StoreSubscriptions
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.analytics.Traits
import com.discord.utilities.billing.GooglePlayBillingManager
import com.discord.utilities.billing.GooglePlaySku
import com.discord.utilities.billing.GooglePlaySku.Section
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils
import rx.Observable
import rx.functions.Func4
import rx.subjects.PublishSubject

class ChoosePlanViewModel(
    private val viewType: WidgetChoosePlan.ViewType,
    private val oldSkuName: String?,
    storeObservable: Observable<StoreState> = observeStores(),
    purchaseEvents: Observable<StoreGooglePlayPurchases.Event> =
        StoreStream.getGooglePlayPurchases().observeEvents(),
) : AppViewModel<ChoosePlanViewModel.ViewState>(ViewState.Loading) {
    private val eventSubject = PublishSubject.create<Event>()
    private var locationTrait: Traits.Location? = null

    data class StoreState(
        val skuDetailsState: StoreGooglePlaySkuDetails.State,
        val purchasesState: StoreGooglePlayPurchases.State,
        val purchasesQueryState: StoreGooglePlayPurchases.QueryState,
        val subscriptionsState: StoreSubscriptions.SubscriptionsState,
    )

    sealed class ViewState {
        data object Loading : ViewState()

        data class Loaded(
            val purchasesQueryState: StoreGooglePlayPurchases.QueryState,
            val items: List<WidgetChoosePlanAdapter.Item>,
            val skuDetails: Map<String, SkuDetails>,
            val purchases: List<Purchase>,
            val subscriptions: List<ModelSubscription>,
            val isEmpty: Boolean,
        ) : ViewState()
    }

    sealed class Event {
        data class CompleteSkuPurchase(val skuName: String, val planName: String) : Event()
        data class ErrorSkuPurchase(@androidx.annotation.StringRes val message: Int) : Event()
        data class StartSkuPurchase(val billingParams: BillingFlowParams) : Event()
    }

    init {
        GooglePlayBillingManager.queryPurchases()
        GooglePlayBillingManager.querySkuDetails()
        storeObservable
            .computationLatest()
            .ui(this)
            .appSubscribe(javaClass) { updateViewState(viewStateFor(viewType, oldSkuName, it)) }
        purchaseEvents.ui(this).appSubscribe(javaClass, onNext = ::handlePurchaseEvent)
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun buy(
        sku: GooglePlaySku,
        oldSkuName: String? = null,
        locationTrait: Traits.Location,
        fromStep: String,
    ) {
        val loaded = viewState as? ViewState.Loaded ?: return
        val newDetails = loaded.skuDetails[sku.skuName]
        val oldDetails = oldSkuName?.let(loaded.skuDetails::get)
        val subscriptionTrait = Traits.Subscription.withGatewayPlanId(sku.skuName)
        this.locationTrait = locationTrait
        if (newDetails == null || oldSkuName != null && oldDetails == null) {
            trackPayment("payment_flow_failed", locationTrait, subscriptionTrait)
            AppLog.e("Purchase details not found. oldSku=$oldSkuName; newSku=${sku.skuName}")
            eventSubject.onNext(Event.ErrorSkuPurchase(R.string.billing_error_purchase_details_not_found))
            return
        }

        var oldPurchaseToken: String? = null
        if (oldSkuName != null && oldDetails != null && oldSkuName != sku.skuName) {
            val purchase = loaded.purchases.firstOrNull { oldSkuName in it.skus }
            if (purchase == null || purchase.purchaseToken.isEmpty()) {
                purchaseFailed(locationTrait, subscriptionTrait, "Subscription without matching purchase: $oldSkuName")
                return
            }
            oldPurchaseToken = purchase.purchaseToken
            val mode = replacementMode(oldDetails, newDetails)
            if (mode == BillingFlowParams.ProrationMode.DEFERRED) {
                val subscriptionId = loaded.subscriptions
                    .firstOrNull { it.paymentGatewayPlanId == oldSkuName }
                    ?.id
                if (subscriptionId == null) {
                    purchaseFailed(locationTrait, subscriptionTrait, "No subscription for downgrade: $oldSkuName")
                    return
                }
                StoreStream.getGooglePlayPurchases().updatePendingDowngrade(
                    PendingDowngrade(purchase.purchaseToken, subscriptionId, sku.skuName),
                )
            }
        }
        val params = billingFlowParams(
            newDetails,
            oldDetails?.takeIf { oldPurchaseToken != null },
            oldPurchaseToken,
            UserUtils.getObfuscatedUserId(StoreStream.getUsers().getMeInternal().id),
        )
        trackPayment(
            "payment_flow_step",
            locationTrait,
            subscriptionTrait,
            mapOf("to_step" to "external_payment", "from_step" to fromStep),
        )
        eventSubject.onNext(Event.StartSkuPurchase(params))
    }

    internal fun handlePurchaseEvent(event: StoreGooglePlayPurchases.Event) {
        val loaded = viewState as? ViewState.Loaded ?: return
        when (event) {
            is StoreGooglePlayPurchases.Event.PurchaseQuerySuccess -> {
                val subscription = Traits.Subscription.withGatewayPlanId(event.newSkuName)
                val planName = loaded.skuDetails[event.newSkuName]?.description ?: return
                trackPayment("payment_flow_completed", locationTrait, subscription)
                eventSubject.onNext(Event.CompleteSkuPurchase(event.newSkuName, planName))
            }
            is StoreGooglePlayPurchases.Event.PurchaseQueryFailure -> {
                val subscription = Traits.Subscription.withGatewayPlanId(event.newSkuName)
                trackPayment("payment_flow_failed", locationTrait, subscription)
                AppLog.e("Purchase query failure: ${event.newSkuName}")
                eventSubject.onNext(Event.ErrorSkuPurchase(R.string.billing_error_purchase))
            }
        }
    }

    private fun purchaseFailed(location: Traits.Location, subscription: Traits.Subscription, message: String) {
        trackPayment("payment_flow_failed", location, subscription)
        AppLog.e(message)
        eventSubject.onNext(Event.ErrorSkuPurchase(R.string.billing_error_purchase))
    }

    private fun trackPayment(
        event: String,
        location: Traits.Location?,
        subscription: Traits.Subscription,
        extra: Map<String, Any> = emptyMap(),
    ) {
        val properties = extra.toMutableMap()
        location?.serializeTo(properties)
        subscription.serializeTo(properties)
        AnalyticsTracker.tracker.track(event, properties)
    }

    companion object {
        private fun observeStores(): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGooglePlaySkuDetails().observeState(),
            StoreStream.getGooglePlayPurchases().observeState(),
            StoreStream.getGooglePlayPurchases().observeQueryState(),
            StoreStream.getSubscriptions().observeSubscriptions(),
            Func4 { skuDetails, purchases, query, subscriptions ->
                StoreState(skuDetails, purchases, query, subscriptions)
            },
        )

        internal fun replacementMode(oldDetails: SkuDetails, newDetails: SkuDetails): Int =
            if (newDetails.priceAmountMicros < oldDetails.priceAmountMicros) {
                BillingFlowParams.ProrationMode.DEFERRED
            } else {
                BillingFlowParams.ProrationMode.IMMEDIATE_WITH_TIME_PRORATION
            }

        internal fun billingFlowParams(
            newDetails: SkuDetails,
            oldDetails: SkuDetails? = null,
            oldPurchaseToken: String? = null,
            obfuscatedAccountId: String? = null,
        ): BillingFlowParams = BillingFlowParams.newBuilder()
            .setSkuDetails(newDetails)
            .apply {
                if (obfuscatedAccountId != null) setObfuscatedAccountId(obfuscatedAccountId)
                if (oldDetails != null) {
                    setSubscriptionUpdateParams(
                        BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                            .setOldPurchaseToken(requireNotNull(oldPurchaseToken))
                            .setReplaceProrationMode(replacementMode(oldDetails, newDetails))
                            .build(),
                    )
                }
            }
            .build()

        internal fun viewStateFor(
            viewType: WidgetChoosePlan.ViewType,
            oldSkuName: String?,
            state: StoreState,
        ): ViewState {
            val skuState = state.skuDetailsState as? StoreGooglePlaySkuDetails.State.Loaded
                ?: return ViewState.Loading
            val purchaseState = state.purchasesState as? StoreGooglePlayPurchases.State.Loaded
                ?: return ViewState.Loading
            val subscriptionState = state.subscriptionsState as? StoreSubscriptions.SubscriptionsState.Loaded
                ?: return ViewState.Loading
            val available = itemsForViewType(viewType, oldSkuName, skuState.skuDetails)
            return ViewState.Loaded(
                purchasesQueryState = state.purchasesQueryState,
                items = currentPlanItems(oldSkuName, skuState.skuDetails) + available,
                skuDetails = skuState.skuDetails,
                purchases = purchaseState.subscriptionPurchases,
                subscriptions = subscriptionState.subscriptions,
                isEmpty = available.isEmpty(),
            )
        }

        internal fun itemsForViewType(
            viewType: WidgetChoosePlan.ViewType,
            oldSkuName: String?,
            skuDetails: Map<String, SkuDetails>,
            interval: SubscriptionInterval = SubscriptionInterval.MONTHLY,
        ): List<WidgetChoosePlanAdapter.Item> {
            val skus = when (viewType) {
                WidgetChoosePlan.ViewType.BUY_PREMIUM_TIER_2 ->
                    GooglePlaySku.entries.filter { it.interval == interval && it.isTier2 }
                WidgetChoosePlan.ViewType.BUY_PREMIUM_TIER_1 ->
                    GooglePlaySku.entries.filter { it.interval == interval && it.isTier1 }
                WidgetChoosePlan.ViewType.BUY_PREMIUM_GUILD -> guildBoostPlans(oldSkuName, interval)
                WidgetChoosePlan.ViewType.SWITCH_PLANS ->
                    GooglePlaySku.entries.filter { it.interval == interval }
            }
            return plansWithHeaders(skus, oldSkuName, skuDetails)
        }

        internal fun currentPlanItems(
            oldSkuName: String?,
            skuDetails: Map<String, SkuDetails>,
        ): List<WidgetChoosePlanAdapter.Item> {
            val current = oldSkuName?.let(GooglePlaySku::fromSkuName) ?: return emptyList()
            val currentDetails = skuDetails[oldSkuName] ?: return emptyList()
            val upgradeDetails = current.upgrade?.let { skuDetails[it.skuName] }
            val downgrade = GooglePlaySku.getDowngrade(oldSkuName)
            val purchaseSku = when {
                current.upgrade != null && upgradeDetails != null -> current
                downgrade != null && skuDetails[downgrade.skuName] != null -> downgrade
                else -> null
            }
            return listOf(
                WidgetChoosePlanAdapter.Item.Header(R.string.billing_switch_plan_current_plan),
                WidgetChoosePlanAdapter.Item.Plan(
                    displaySku = current,
                    skuDetails = currentDetails,
                    purchaseSku = purchaseSku,
                    oldSkuName = oldSkuName,
                    upgradeSkuDetails = upgradeDetails,
                    isCurrentPlan = true,
                ),
                WidgetChoosePlanAdapter.Item.Divider,
            )
        }

        private fun guildBoostPlans(
            oldSkuName: String?,
            interval: SubscriptionInterval,
        ): List<GooglePlaySku> {
            val current = oldSkuName?.let(GooglePlaySku::fromSkuName)
            return if (current == null) {
                GooglePlaySku.entries.filter { it.interval == interval && it.premiumSubscriptionCount > 0 }
            } else {
                GooglePlaySku.entries.filter {
                    it.interval == current.interval &&
                        it.premiumSubscriptionCount > current.premiumSubscriptionCount
                }
            }
        }

        private fun plansWithHeaders(
            skus: List<GooglePlaySku>,
            oldSkuName: String?,
            skuDetails: Map<String, SkuDetails>,
        ): List<WidgetChoosePlanAdapter.Item> = buildList {
            listOf(Section.PREMIUM, Section.PREMIUM_AND_PREMIUM_GUILD, Section.PREMIUM_GUILD)
                .map { section -> skus.filter { it.section == section } }
                .filter { it.isNotEmpty() }
                .forEach { sectionSkus ->
                    add(WidgetChoosePlanAdapter.Item.Header(sectionSkus.first().section.headerResource))
                    sectionSkus.mapNotNullTo(this) { sku -> plan(sku, oldSkuName, skuDetails) }
                }
        }

        private fun plan(
            sku: GooglePlaySku,
            oldSkuName: String?,
            skuDetails: Map<String, SkuDetails>,
        ): WidgetChoosePlanAdapter.Item.Plan? {
            val details = skuDetails[sku.skuName] ?: return null
            val isCurrent = oldSkuName == sku.skuName
            val oldIsUpgrade = oldSkuName == sku.upgrade?.skuName && sku.type != GooglePlaySku.Type.PREMIUM_GUILD
            if (oldSkuName != null && (isCurrent || oldIsUpgrade)) return null
            return WidgetChoosePlanAdapter.Item.Plan(
                displaySku = sku,
                skuDetails = details,
                purchaseSku = sku,
                oldSkuName = oldSkuName,
                upgradeSkuDetails = sku.upgrade?.let { skuDetails[it.skuName] },
            )
        }
    }
}
