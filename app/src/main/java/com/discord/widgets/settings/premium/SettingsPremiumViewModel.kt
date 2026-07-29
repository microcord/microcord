package com.discord.widgets.settings.premium

import com.android.billingclient.api.Purchase
import com.android.billingclient.api.SkuDetails
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelEntitlement
import com.discord.models.domain.ModelGuildBoostSlot
import com.discord.models.domain.ModelPaymentSource
import com.discord.models.domain.ModelSubscription
import com.discord.models.domain.billing.ModelInvoicePreview
import com.discord.models.domain.premium.SubscriptionPlanType
import com.discord.stores.StoreEntitlements
import com.discord.stores.StoreGooglePlayPurchases
import com.discord.stores.StoreGooglePlaySkuDetails
import com.discord.stores.StoreGuildBoost
import com.discord.stores.StorePaymentSources
import com.discord.stores.StoreSubscriptions
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable

/** Complete 126.21 premium-screen state projection, without billing-client operations. */
class SettingsPremiumViewModel(
    storeObservable: Observable<StoreState>,
) : AppViewModel<SettingsPremiumViewModel.ViewState>(ViewState.Loading) {

    sealed class InvoicePreviewFetch {
        data class Invoice(val modelInvoicePreview: ModelInvoicePreview?) : InvoicePreviewFetch()
        data object Error : InvoicePreviewFetch()
    }

    data class StoreState(
        val paymentSourcesState: StorePaymentSources.PaymentSourcesState,
        val subscriptionsState: StoreSubscriptions.SubscriptionsState,
        val entitlementState: StoreEntitlements.State,
        val guildBoostState: StoreGuildBoost.State,
        val currentInvoicePreviewFetch: InvoicePreviewFetch,
        val renewalInvoicePreviewFetch: InvoicePreviewFetch,
        val skuDetailsState: StoreGooglePlaySkuDetails.State,
        val purchaseState: StoreGooglePlayPurchases.State,
    )

    sealed class ViewState {
        data object Loading : ViewState()
        data object Failure : ViewState()

        data class Loaded(
            val premiumSubscription: ModelSubscription?,
            val paymentSources: List<ModelPaymentSource>,
            val isBusy: Boolean,
            val entitlements: List<ModelEntitlement>,
            val guildSubscriptions: Map<Long, ModelGuildBoostSlot>,
            val hasAnyGuildBoosts: Boolean,
            val renewalInvoicePreview: ModelInvoicePreview?,
            val currentInvoicePreview: ModelInvoicePreview?,
            val skuDetails: Map<String, SkuDetails>,
            val purchases: List<Purchase>,
            val pastDueGracePeriodDays: Int,
        ) : ViewState()
    }

    init {
        storeObservable
            .computationLatest()
            .ui(this)
            .subscribe { updateViewState(viewStateFor(it)) }
    }

    fun markBusy() {
        (viewState as? ViewState.Loaded)?.let { updateViewState(it.copy(isBusy = true)) }
    }

    companion object {
        private const val PREMIUM_APPLICATION_ID = 521842831262875670L
        private val GUILD_PLAN_IDS = setOf(
            SubscriptionPlanType.PREMIUM_GUILD_MONTH.planId,
            SubscriptionPlanType.PREMIUM_GUILD_3_MONTH.planId,
            SubscriptionPlanType.PREMIUM_GUILD_6_MONTH.planId,
            SubscriptionPlanType.PREMIUM_GUILD_YEAR.planId,
        )

        internal fun viewStateFor(state: StoreState): ViewState {
            val paymentSources = state.paymentSourcesState as? StorePaymentSources.PaymentSourcesState.Loaded
            val subscriptions = state.subscriptionsState as? StoreSubscriptions.SubscriptionsState.Loaded
            val entitlements = state.entitlementState as? StoreEntitlements.State.Loaded
            val guildBoosts = state.guildBoostState as? StoreGuildBoost.State.Loaded
            val renewalInvoice = state.renewalInvoicePreviewFetch as? InvoicePreviewFetch.Invoice
            val currentInvoice = state.currentInvoicePreviewFetch as? InvoicePreviewFetch.Invoice
            val skuDetails = state.skuDetailsState as? StoreGooglePlaySkuDetails.State.Loaded
            val purchases = state.purchaseState as? StoreGooglePlayPurchases.State.Loaded

            if (
                paymentSources != null && subscriptions != null && entitlements != null &&
                guildBoosts != null && renewalInvoice != null && currentInvoice != null &&
                skuDetails != null && purchases != null
            ) {
                val subscription = subscriptions.premiumSubscription
                return ViewState.Loaded(
                    premiumSubscription = subscription,
                    paymentSources = paymentSources.paymentSources,
                    isBusy = false,
                    entitlements = entitlements.ownedEntitlements[PREMIUM_APPLICATION_ID].orEmpty(),
                    guildSubscriptions = guildBoosts.boostSlotMap,
                    hasAnyGuildBoosts = subscription?.hasAnyOfPlans(GUILD_PLAN_IDS) == true,
                    renewalInvoicePreview = renewalInvoice.modelInvoicePreview,
                    currentInvoicePreview = currentInvoice.modelInvoicePreview,
                    skuDetails = skuDetails.skuDetails,
                    purchases = purchases.subscriptionPurchases,
                    pastDueGracePeriodDays = pastDueGracePeriodDays(subscription),
                )
            }

            return if (
                state.paymentSourcesState is StorePaymentSources.PaymentSourcesState.Failure ||
                state.subscriptionsState is StoreSubscriptions.SubscriptionsState.Failure ||
                state.guildBoostState is StoreGuildBoost.State.Failure ||
                state.renewalInvoicePreviewFetch is InvoicePreviewFetch.Error ||
                state.skuDetailsState is StoreGooglePlaySkuDetails.State.Failure
            ) ViewState.Failure else ViewState.Loading
        }

        internal fun pastDueGracePeriodDays(subscription: ModelSubscription?): Int =
            if (subscription != null && !subscription.isMobileManaged() && !subscription.paymentSourceId.isNullOrBlank()) 7 else 3
    }
}
