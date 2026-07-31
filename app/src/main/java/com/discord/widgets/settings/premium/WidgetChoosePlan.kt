package com.discord.widgets.settings.premium

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.billingclient.api.SkuDetails
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.i18n.i18nPlural
import com.discord.stores.StoreGooglePlayPurchases
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.analytics.Traits
import com.discord.utilities.billing.GooglePlayBillingManager
import com.discord.utilities.billing.GooglePlaySku
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.premium.BlockRussianPurchasesUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.ToastManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.NumberFormat

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
    private lateinit var adapter: WidgetChoosePlanAdapter
    private lateinit var emptyContainer: LinearLayout
    private lateinit var dimmer: DimmerView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val recycler = view.findViewById<RecyclerView>(R.id.choose_plan_recycler)
        emptyContainer = view.findViewById(R.id.choose_plan_empty_container)
        dimmer = view.findViewById(R.id.dimmer_view)
        adapter = WidgetChoosePlanAdapter(recycler, ::selectPlan)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).appSubscribe(javaClass, onNext = ::render)
        viewModel.observeEvents().ui(this).appSubscribe(javaClass, onNext = ::handleEvent)
    }

    override fun onDestroyView() {
        toastManager.close()
        super.onDestroyView()
    }

    fun render(viewState: ChoosePlanViewModel.ViewState) {
        if (!::adapter.isInitialized) return
        when (viewState) {
            ChoosePlanViewModel.ViewState.Loading -> {
                adapter.setData(emptyList())
                emptyContainer.visibility = View.GONE
                dimmer.setDimmed(true)
            }
            is ChoosePlanViewModel.ViewState.Loaded -> {
                adapter.setData(viewState.items)
                emptyContainer.visibility = if (viewState.isEmpty) View.VISIBLE else View.GONE
                dimmer.setDimmed(viewState.purchasesQueryState is StoreGooglePlayPurchases.QueryState.InProgress)
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
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.guild_boost_upgrade_dialog, null)
        val dialog = MaterialAlertDialogBuilder(requireContext()).setView(view).create()
        view.findViewById<TextView>(R.id.premium_upsell_description).text =
            getString(R.string.premium_upsell_feature_pretext).replace("{monthlyPrice}", upgradeDetails.price)
        view.findViewById<TextView>(R.id.premium_upsell_server_perk).text =
            getString(R.string.premium_upsell_feature_free_guild_subscription).replace(
                "{numFreeGuildSubscriptions}",
                requireContext().i18nPlural(
                    R.plurals.premium_upsell_feature_free_guild_subscription_numFreeGuildSubscriptions,
                    2,
                    "#" to 2,
                ),
            )
        view.findViewById<TextView>(R.id.continue_button).apply {
            visibility = if (sku.skuName == oldSkuName) View.GONE else View.VISIBLE
            setOnClickListener {
                dialog.dismiss()
                viewModel.buy(sku, oldSkuName, locationTrait, STEP_PREMIUM_UPSELL)
            }
        }
        view.findViewById<MaterialButton>(R.id.upgrade_button).setOnClickListener {
            dialog.dismiss()
            val modalLocation = Traits.Location(page = "Premium Upsell Modal", section = "Premium Upsell Modal")
            trackPayment("payment_flow_started", modalLocation, requireNotNull(sku.upgrade).skuName)
            viewModel.buy(sku.upgrade, oldSkuName, modalLocation, STEP_PREMIUM_UPSELL)
        }
        view.findViewById<ImageView>(R.id.premium_upsell_close).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showYearlyUpgrade(sku: GooglePlaySku, oldSkuName: String?, upgradeDetails: SkuDetails) {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.premium_upgrade_yearly_dialog, null)
        val dialog = MaterialAlertDialogBuilder(requireContext()).setView(view).create()
        val discount = NumberFormat.getPercentInstance().format(0.16f)
        val tierName = getString(if (sku.isTier1) R.string.premium_tier_1 else R.string.premium_tier_2)
        view.findViewById<TextView>(R.id.premium_upsell_yearly_title).text =
            getString(R.string.premium_upsell_yearly_title)
                .replace("{discountPercentage}", discount)
                .replace("{planName}", tierName)
        view.findViewById<TextView>(R.id.premium_upsell_yearly_description).text =
            getString(R.string.premium_upsell_yearly_description).replace("{discountPercentage}", discount)
        view.findViewById<ImageView>(R.id.premium_upsell_header).setImageResource(
            if (sku.isTier1) R.drawable.img_premium_guild_subscription_purchase_header_tier_1
            else R.drawable.img_premium_guild_subscription_purchase_header_tier_2,
        )
        view.findViewById<TextView>(R.id.purchase_premium_monthly).apply {
            visibility = if (sku.skuName == oldSkuName) View.GONE else View.VISIBLE
            setOnClickListener {
                dialog.dismiss()
                viewModel.buy(sku, oldSkuName, locationTrait, STEP_YEARLY_UPSELL)
            }
        }
        view.findViewById<MaterialButton>(R.id.purchase_premium_yearly).apply {
            text = getString(R.string.premium_upsell_yearly_cta).replace("!!{price}!!", upgradeDetails.price)
            setOnClickListener {
                dialog.dismiss()
                val modalLocation = Traits.Location(page = "Yearly Upsell Modal", section = "Yearly Upsell Modal")
                trackPayment("payment_flow_started", modalLocation, requireNotNull(sku.upgrade).skuName)
                viewModel.buy(sku.upgrade, oldSkuName, modalLocation, STEP_YEARLY_UPSELL)
            }
        }
        dialog.setOnCancelListener { AnalyticsTracker.upgradePremiumYearlyClosed(locationTrait) }
        dialog.show()
    }

    private fun handleEvent(event: ChoosePlanViewModel.Event) {
        when (event) {
            is ChoosePlanViewModel.Event.ErrorSkuPurchase -> toastManager.show(requireContext(), event.message)
            is ChoosePlanViewModel.Event.StartSkuPurchase ->
                GooglePlayBillingManager.launchBillingFlow(requireAppActivity(), event.billingParams)
            is ChoosePlanViewModel.Event.CompleteSkuPurchase -> {
                GooglePlayBillingManager.queryPurchases()
                requireAppActivity().setResult(Activity.RESULT_OK)
                requireAppActivity().onBackPressedDispatcher.onBackPressed()
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

    companion object {
        const val RESULT_LOCATION_TRAIT = "result_extra_location_trait"
        const val RESULT_OLD_SKU_NAME = "result_extra_current_sku_name"
        const val RESULT_VIEW_TYPE = "result_view_type"
        private const val STEP_PLAN_SELECT = "plan_select"
        private const val STEP_PREMIUM_UPSELL = "premium_upsell"
        private const val STEP_YEARLY_UPSELL = "yearly_upsell"

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
