package com.discord.widgets.settings.premium

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppViewFlipper
import com.discord.models.domain.ModelSubscription
import com.discord.models.domain.premium.SubscriptionPlanType
import com.discord.utilities.premium.BlockRussianPurchasesUtils
import com.discord.views.ActiveSubscriptionView
import com.discord.views.premium.AccountCreditView
import com.google.android.material.button.MaterialButton
import java.text.NumberFormat
import java.util.Currency
import com.discord.i18n.i18nRender

/** Premium settings presentation. Billing and REST actions are emitted to the owning host. */
class WidgetSettingsPremium : AppFragment(R.layout.widget_settings_premium) {
    private lateinit var root: View

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        root = view
        setActionBarTitle(R.string.premium_settings)
        view.findViewById<View>(R.id.premium_settings_retry).setOnClickListener { emit(ACTION_RETRY) }
        view.findViewById<View>(R.id.premium_settings_premium_classic).setOnClickListener {
            emit(ACTION_CHOOSE_PLAN, WidgetChoosePlan.ViewType.BUY_PREMIUM_TIER_1)
        }
        view.findViewById<View>(R.id.premium_settings_premium).setOnClickListener {
            emit(ACTION_CHOOSE_PLAN, WidgetChoosePlan.ViewType.BUY_PREMIUM_TIER_2)
        }
        view.findViewById<View>(R.id.premium_settings_google_play_manage).setOnClickListener {
            emit(ACTION_MANAGE_GOOGLE_PLAY)
        }
    }

    fun render(viewState: SettingsPremiumViewModel.ViewState) {
        if (!::root.isInitialized) return
        val flipper = root.findViewById<AppViewFlipper>(R.id.premium_settings_view_flipper)
        when (viewState) {
            SettingsPremiumViewModel.ViewState.Loading -> flipper.displayedChild = VIEW_LOADING
            SettingsPremiumViewModel.ViewState.Failure -> flipper.displayedChild = VIEW_ERROR
            is SettingsPremiumViewModel.ViewState.Loaded -> {
                flipper.displayedChild = VIEW_CONTENT
                renderContent(viewState)
            }
        }
    }

    private fun renderContent(state: SettingsPremiumViewModel.ViewState.Loaded) {
        val subscription = state.premiumSubscription
        val hasSubscription = subscription != null
        root.findViewById<LinearLayout>(R.id.premium_settings_subscription_container).visibility =
            if (hasSubscription || state.hasAnyGuildBoosts) View.VISIBLE else View.GONE
        root.findViewById<LinearLayout>(R.id.premium_settings_payment_container).visibility =
            if (hasSubscription) View.VISIBLE else View.GONE
        root.findViewById<CardView>(R.id.premium_settings_status_notice_container).visibility = View.GONE
        root.findViewById<View>(R.id.premium_settings_price_change_notice).visibility = View.GONE
        root.findViewById<TextView>(R.id.premium_settings_legalese).visibility =
            if (hasSubscription) View.VISIBLE else View.GONE

        configurePremiumSubscription(state)
        configureGuildBoostSubscription(state)
        configureCredits(state)
        configurePurchaseButtons(state)
        configurePayment(state)
    }

    private fun configurePremiumSubscription(state: SettingsPremiumViewModel.ViewState.Loaded) {
        val subscription = state.premiumSubscription
        root.findViewById<ActiveSubscriptionView>(R.id.premium_settings_active_subscription).apply {
            visibility = if (subscription == null) View.GONE else View.VISIBLE
            if (subscription == null) return@apply
            configure(
                activeSubscriptionType = ActiveSubscriptionView.fromSubscription(subscription),
                status = subscription.status,
                isTrialSubscription = subscription.trialId != null,
                priceText = priceText(state, subscription),
                isLoading = state.isBusy,
                guildBoostCount = subscription.premiumAdditionalPlans.sumOf { it.quantity },
                topBtnCallback = {
                    emit(
                        if (subscription.isMobileManaged()) ACTION_MANAGE_SUBSCRIPTION else ACTION_SWITCH_PLAN,
                        oldSkuName = subscription.paymentGatewayPlanId,
                    )
                },
                manageGuildBoostBtnCallback = { emit(ACTION_MANAGE_GUILD_BOOSTS) },
                cancelBtnCallback = if (subscription.isMobileManaged()) null else {
                    { emit(ACTION_CANCEL_SUBSCRIPTION) }
                },
                isBasePlanMutatingToNonePlan = subscription.renewalMutations?.premiumBasePlanId in NONE_PLAN_IDS,
            )
        }
    }

    private fun configureGuildBoostSubscription(state: SettingsPremiumViewModel.ViewState.Loaded) {
        root.findViewById<ActiveSubscriptionView>(R.id.premium_settings_active_guild_subscription).apply {
            visibility = if (state.hasAnyGuildBoosts) View.VISIBLE else View.GONE
            if (!state.hasAnyGuildBoosts) return@apply
            val subscription = state.premiumSubscription
            configure(
                activeSubscriptionType = ActiveSubscriptionView.ActiveSubscriptionType.PREMIUM_GUILD,
                status = subscription?.status ?: ModelSubscription.Status.ACTIVE,
                priceText = priceText(state, subscription),
                isLoading = state.isBusy,
                guildBoostCount = state.guildSubscriptions.size,
                topBtnCallback = { emit(ACTION_SWITCH_PLAN, oldSkuName = subscription?.paymentGatewayPlanId) },
                manageGuildBoostBtnCallback = { emit(ACTION_MANAGE_GUILD_BOOSTS) },
                cancelBtnCallback = null,
            )
        }
    }

    private fun configureCredits(state: SettingsPremiumViewModel.ViewState.Loaded) {
        val classic = state.entitlements.count {
            it.parentId != null && it.subscriptionPlan?.id == SubscriptionPlanType.PREMIUM_MONTH_TIER_1.planId
        }
        val nitro = state.entitlements.count {
            it.parentId != null && it.subscriptionPlan?.id == SubscriptionPlanType.PREMIUM_MONTH_TIER_2.planId
        }
        root.findViewById<LinearLayout>(R.id.premium_settings_credit_container).visibility =
            if (classic + nitro > 0) View.VISIBLE else View.GONE
        root.findViewById<AccountCreditView>(R.id.credit_nitro_classic)
            .bind(SubscriptionPlanType.PREMIUM_MONTH_TIER_1.planId, classic, state.premiumSubscription)
        root.findViewById<AccountCreditView>(R.id.credit_nitro)
            .bind(SubscriptionPlanType.PREMIUM_MONTH_TIER_2.planId, nitro, state.premiumSubscription)
        root.findViewById<View>(R.id.credit_nitro_divider).visibility =
            if (classic > 0 && nitro > 0) View.VISIBLE else View.GONE
    }

    private fun configurePurchaseButtons(state: SettingsPremiumViewModel.ViewState.Loaded) {
        val subscription = state.premiumSubscription
        val enabled = subscription == null
        val classic = root.findViewById<MaterialButton>(R.id.premium_settings_premium_classic)
        val nitro = root.findViewById<MaterialButton>(R.id.premium_settings_premium)
        classic.isEnabled = enabled
        nitro.isEnabled = enabled
        classic.text = subscribeText(state.skuDetails["premium_tier_1_monthly"]?.price)
        nitro.text = subscribeText(state.skuDetails["premium_tier_2_monthly"]?.price)
    }

    private fun configurePayment(state: SettingsPremiumViewModel.ViewState.Loaded) {
        val subscription = state.premiumSubscription ?: return
        root.findViewById<TextView>(R.id.premium_settings_billing_information).text =
            when (subscription.status) {
                ModelSubscription.Status.CANCELED -> requireContext().i18nRender(R.string.premium_settings_cancelled_info)
                ModelSubscription.Status.PAST_DUE -> requireContext().i18nRender(R.string.premium_settings_past_due_info)
                ModelSubscription.Status.ACCOUNT_HOLD -> requireContext().i18nRender(R.string.premium_settings_account_hold_info)
                else -> getString(R.string.premium_settings_renewal_info)
            }
        root.findViewById<TextView>(R.id.premium_settings_google_play_manage).visibility =
            if (subscription.isGoogleSubscription()) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.premium_settings_billing_divider).visibility =
            if (subscription.isAppleSubscription()) View.VISIBLE else View.GONE
        root.findViewById<MaterialButton>(R.id.active_subscriptions_bottom_button).apply {
            visibility = if (subscription.isAppleSubscription()) View.VISIBLE else View.GONE
            setText(R.string.billing_manage_subscription)
            setOnClickListener { emit(ACTION_MANAGE_APPLE) }
        }
    }

    private fun subscribeText(price: String?): String = if (price == null) {
        getString(R.string.premium_settings_subscribe_today)
    } else {
        getString(R.string.premium_settings_starting_at_per_month).replace("!!{price}!!", price)
    }

    private fun priceText(
        state: SettingsPremiumViewModel.ViewState.Loaded,
        subscription: ModelSubscription?,
    ): String {
        state.skuDetails[subscription?.paymentGatewayPlanId]?.price?.let { return it }
        val invoice = state.currentInvoicePreview ?: return ""
        return NumberFormat.getCurrencyInstance().apply {
            runCatching { Currency.getInstance(invoice.currency) }
                .getOrNull()
                ?.let { currency = it }
        }.format(invoice.total / 100.0)
    }

    private fun emit(
        action: String,
        viewType: WidgetChoosePlan.ViewType? = null,
        oldSkuName: String? = null,
    ) {
        parentFragmentManager.setFragmentResult(
            RESULT_ACTION,
            Bundle().apply {
                putString(RESULT_ACTION_NAME, action)
                putSerializable(RESULT_VIEW_TYPE, viewType)
                putString(WidgetChoosePlan.RESULT_OLD_SKU_NAME, oldSkuName)
            },
        )
    }

    companion object {
        private const val INTENT_SCROLL_TO_SECTION = "intent_section"
        private const val ANALYTICS_LOCATION_SECTION = "analytics_location_section"
        const val RESULT_ACTION = "premium_settings_action"
        const val RESULT_ACTION_NAME = "action"
        const val RESULT_VIEW_TYPE = "result_view_type"
        const val ACTION_RETRY = "retry"
        const val ACTION_CHOOSE_PLAN = "choose_plan"
        const val ACTION_SWITCH_PLAN = "switch_plan"
        const val ACTION_MANAGE_SUBSCRIPTION = "manage_subscription"
        const val ACTION_MANAGE_GOOGLE_PLAY = "manage_google_play"
        const val ACTION_MANAGE_APPLE = "manage_apple"
        const val ACTION_MANAGE_GUILD_BOOSTS = "manage_guild_boosts"
        const val ACTION_CANCEL_SUBSCRIPTION = "cancel_subscription"

        private const val VIEW_CONTENT = 0
        private const val VIEW_LOADING = 1
        private const val VIEW_ERROR = 2
        private val NONE_PLAN_IDS = setOf(
            SubscriptionPlanType.NONE_MONTH.planId,
            SubscriptionPlanType.NONE_3_MONTH.planId,
            SubscriptionPlanType.NONE_6_MONTH.planId,
            SubscriptionPlanType.NONE_YEAR.planId,
        )

        @JvmOverloads
        fun launch(
            context: Context,
            scrollToSection: Int? = null,
            locationSection: String? = null,
        ) {
            if (BlockRussianPurchasesUtils.showDialog()) return
            AppScreenRouter.launch(
                context,
                WidgetSettingsPremium::class.java,
                Intent()
                    .putExtra(INTENT_SCROLL_TO_SECTION, scrollToSection)
                    .putExtra(ANALYTICS_LOCATION_SECTION, locationSection),
            )
        }
    }
}
