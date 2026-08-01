package com.discord.widgets.settings.premium

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.billingclient.api.SkuDetails
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.databinding.WidgetChoosePlanBinding
import com.discord.stores.StoreGooglePlayPurchases
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.analytics.Traits
import com.discord.utilities.billing.GooglePlayBillingManager
import com.discord.utilities.billing.GooglePlaySku
import com.discord.utilities.premium.BlockRussianPurchasesUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.ToastManager
import com.discord.widgets.servers.guildboost.GuildBoostActivatedDialog

class WidgetChoosePlan : AppFragment(R.layout.widget_choose_plan) {
    @Suppress("DEPRECATION")
    private val viewType: ViewType by lazy {
        requireNotNull(mostRecentIntent.getSerializableExtra(RESULT_VIEW_TYPE) as? ViewType)
    }
    private val oldSkuName by lazy { mostRecentIntent.getStringExtra(RESULT_OLD_SKU_NAME) }
    @Suppress("DEPRECATION")
    private val locationTrait: Traits.Location by lazy {
        requireNotNull(mostRecentIntent.getSerializableExtra(RESULT_LOCATION_TRAIT) as? Traits.Location)
    }
    private val viewModel: ChoosePlanViewModel by appViewModels { ChoosePlanViewModel(viewType, oldSkuName) }
    private val toastManager = ToastManager()
    private var binding: WidgetChoosePlanBinding? = null
    private lateinit var adapter: WidgetChoosePlanAdapter

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = WidgetChoosePlanBinding.bind(view).also { this.binding = it }
        adapter = WidgetChoosePlanAdapter(binding.choosePlanRecycler, ::selectPlan)
        binding.choosePlanRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.choosePlanRecycler.adapter = adapter
        parentFragmentManager.setFragmentResultListener(GuildBoostUpgradeDialog.REQUEST_KEY, this) { _, result ->
            val sku = result.getString(GuildBoostUpgradeDialog.RESULT_SKU_NAME)
                ?.let(GooglePlaySku::fromSkuName)
                ?: return@setFragmentResultListener
            when (result.getString(GuildBoostUpgradeDialog.RESULT_ACTION)) {
                GuildBoostUpgradeDialog.ACTION_CONTINUE ->
                    viewModel.buy(sku, oldSkuName, locationTrait, STEP_PREMIUM_UPSELL)
                GuildBoostUpgradeDialog.ACTION_UPGRADE -> {
                    val modalLocation = Traits.Location(page = "Premium Upsell Modal", section = "Premium Upsell Modal")
                    val upgrade = sku.upgrade ?: return@setFragmentResultListener
                    trackPayment("payment_flow_started", modalLocation, upgrade.skuName)
                    viewModel.buy(upgrade, oldSkuName, modalLocation, STEP_PREMIUM_UPSELL)
                }
            }
        }
        parentFragmentManager.setFragmentResultListener(PremiumUpgradeYearlyDialog.REQUEST_KEY, this) { _, result ->
            val sku = result.getString(PremiumUpgradeYearlyDialog.RESULT_SKU_NAME)
                ?.let(GooglePlaySku::fromSkuName)
                ?: return@setFragmentResultListener
            when (result.getString(PremiumUpgradeYearlyDialog.RESULT_ACTION)) {
                PremiumUpgradeYearlyDialog.ACTION_MONTHLY ->
                    viewModel.buy(sku, oldSkuName, locationTrait, STEP_YEARLY_UPSELL)
                PremiumUpgradeYearlyDialog.ACTION_YEARLY -> {
                    val modalLocation = Traits.Location(page = "Yearly Upsell Modal", section = "Yearly Upsell Modal")
                    val upgrade = sku.upgrade ?: return@setFragmentResultListener
                    trackPayment("payment_flow_started", modalLocation, upgrade.skuName)
                    viewModel.buy(upgrade, oldSkuName, modalLocation, STEP_YEARLY_UPSELL)
                }
                PremiumUpgradeYearlyDialog.ACTION_CANCEL ->
                    AnalyticsTracker.upgradePremiumYearlyClosed(locationTrait)
            }
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).appSubscribe(javaClass, onNext = ::render)
        viewModel.observeEvents().ui(this).appSubscribe(javaClass, onNext = ::handleEvent)
    }

    override fun onDestroyView() {
        toastManager.close()
        binding = null
        super.onDestroyView()
    }

    fun render(viewState: ChoosePlanViewModel.ViewState) {
        if (!::adapter.isInitialized) return
        when (viewState) {
            ChoosePlanViewModel.ViewState.Loading -> {
                adapter.setData(emptyList())
                binding?.choosePlanEmptyContainer?.visibility = View.GONE
                binding?.dimmerView?.setDimmed(true)
            }
            is ChoosePlanViewModel.ViewState.Loaded -> {
                adapter.setData(viewState.items)
                binding?.choosePlanEmptyContainer?.visibility = if (viewState.isEmpty) View.VISIBLE else View.GONE
                binding?.dimmerView?.setDimmed(viewState.purchasesQueryState is StoreGooglePlayPurchases.QueryState.InProgress)
            }
        }
    }

    private fun selectPlan(sku: GooglePlaySku, oldSkuName: String?, upgradeDetails: SkuDetails?) {
        val upgrade = sku.upgrade
        if (upgrade == null || upgradeDetails == null) {
            viewModel.buy(sku, oldSkuName, locationTrait, STEP_PLAN_SELECT)
            return
        }
        trackPayment("payment_flow_step", locationTrait, sku.skuName, STEP_PLAN_SELECT, upsellStep(sku))
        if (sku.type == GooglePlaySku.Type.PREMIUM_GUILD) {
            showGuildBoostUpgrade(sku, oldSkuName, upgradeDetails)
        } else {
            showYearlyUpgrade(sku, oldSkuName, upgradeDetails)
        }
    }

    private fun showGuildBoostUpgrade(sku: GooglePlaySku, oldSkuName: String?, upgradeDetails: SkuDetails) {
        GuildBoostUpgradeDialog.show(
            parentFragmentManager,
            sku.skuName,
            upgradeDetails.price,
            sku.skuName == oldSkuName,
        )
    }

    private fun showYearlyUpgrade(sku: GooglePlaySku, oldSkuName: String?, upgradeDetails: SkuDetails) {
        PremiumUpgradeYearlyDialog.show(
            parentFragmentManager,
            sku.skuName,
            upgradeDetails.price,
            sku.skuName == oldSkuName,
            sku.isTier1,
        )
    }

    private fun handleEvent(event: ChoosePlanViewModel.Event) {
        when (event) {
            is ChoosePlanViewModel.Event.ErrorSkuPurchase -> toastManager.show(requireContext(), event.message)
            is ChoosePlanViewModel.Event.StartSkuPurchase ->
                GooglePlayBillingManager.launchBillingFlow(requireAppActivity(), event.billingParams)
            is ChoosePlanViewModel.Event.CompleteSkuPurchase -> {
                val sku = GooglePlaySku.fromSkuName(event.skuName) ?: return
                val finish = {
                    GooglePlayBillingManager.queryPurchases()
                    requireAppActivity().setResult(Activity.RESULT_OK)
                    requireAppActivity().onBackPressedDispatcher.onBackPressed()
                }
                when (activationDialog(sku.type)) {
                    ActivationDialog.PREMIUM_TIER_2 ->
                        PremiumActivatedDialog.show(parentFragmentManager, false, finish)
                    ActivationDialog.PREMIUM_TIER_1 ->
                        PremiumActivatedDialog.show(parentFragmentManager, true, finish)
                    ActivationDialog.PREMIUM_AND_GUILD_BOOST ->
                        PremiumAndGuildBoostActivatedDialog.show(parentFragmentManager, event.planName, finish)
                    ActivationDialog.GUILD_BOOST -> GuildBoostActivatedDialog.show(
                        parentFragmentManager,
                        requireContext(),
                        null,
                        sku.premiumSubscriptionCount,
                        false,
                        finish,
                    )
                }
            }
        }
    }

    private fun upsellStep(sku: GooglePlaySku) =
        if (sku.type == GooglePlaySku.Type.PREMIUM_GUILD) STEP_PREMIUM_UPSELL else STEP_YEARLY_UPSELL

    enum class ViewType {
        BUY_PREMIUM_TIER_2,
        BUY_PREMIUM_TIER_1,
        BUY_PREMIUM_GUILD,
        SWITCH_PLANS,
    }

    internal enum class ActivationDialog {
        PREMIUM_TIER_2,
        PREMIUM_TIER_1,
        PREMIUM_AND_GUILD_BOOST,
        GUILD_BOOST,
    }

    companion object {
        const val RESULT_LOCATION_TRAIT = "result_extra_location_trait"
        const val RESULT_OLD_SKU_NAME = "result_extra_current_sku_name"
        const val RESULT_VIEW_TYPE = "result_view_type"
        private const val STEP_PLAN_SELECT = "plan_select"
        private const val STEP_PREMIUM_UPSELL = "premium_upsell"
        private const val STEP_YEARLY_UPSELL = "yearly_upsell"

        internal fun activationDialog(type: GooglePlaySku.Type): ActivationDialog = when (type) {
            GooglePlaySku.Type.PREMIUM_TIER_2 -> ActivationDialog.PREMIUM_TIER_2
            GooglePlaySku.Type.PREMIUM_TIER_1 -> ActivationDialog.PREMIUM_TIER_1
            GooglePlaySku.Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD,
            GooglePlaySku.Type.PREMIUM_TIER_1_AND_PREMIUM_GUILD,
            -> ActivationDialog.PREMIUM_AND_GUILD_BOOST
            GooglePlaySku.Type.PREMIUM_GUILD -> ActivationDialog.GUILD_BOOST
        }

        fun launch(
            context: Context,
            launcher: ActivityResultLauncher<Intent>? = null,
            viewType: ViewType,
            oldSkuName: String? = null,
            locationTrait: Traits.Location,
            subscriptionTrait: Traits.Subscription? = null,
        ) {
            if (BlockRussianPurchasesUtils.showDialog()) return
            val intent = Intent()
                .putExtra(RESULT_OLD_SKU_NAME, oldSkuName)
                .putExtra(RESULT_LOCATION_TRAIT, locationTrait)
                .putExtra(RESULT_VIEW_TYPE, viewType)
            val properties = mutableMapOf<String, Any>()
            locationTrait.serializeTo(properties)
            subscriptionTrait?.serializeTo(properties)
            AnalyticsTracker.tracker.track("payment_flow_started", properties)
            if (launcher == null) {
                AppScreenRouter.launch(context, WidgetChoosePlan::class.java, intent)
            } else {
                AppScreenRouter.launchForResult(context, launcher, WidgetChoosePlan::class.java, intent)
            }
        }

        fun registerForResult(
            fragment: AppFragment,
            callback: () -> Unit,
        ): ActivityResultLauncher<Intent> = fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result -> if (result.resultCode == Activity.RESULT_OK) callback() }

        private fun trackPayment(
            event: String,
            location: Traits.Location,
            skuName: String,
            fromStep: String? = null,
            toStep: String? = null,
        ) {
            val properties = mutableMapOf<String, Any>()
            location.serializeTo(properties)
            Traits.Subscription.withGatewayPlanId(skuName).serializeTo(properties)
            fromStep?.let { properties["from_step"] = it }
            toStep?.let { properties["to_step"] = it }
            AnalyticsTracker.tracker.track(event, properties)
        }
    }
}
