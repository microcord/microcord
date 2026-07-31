package com.discord.widgets.settings.premium

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ProgressBar
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppViewFlipper
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreGooglePlaySkuDetails
import com.discord.stores.StoreStream
import com.discord.utilities.billing.InAppSkuConfig
import com.discord.utilities.billing.premiumGifts
import com.discord.utilities.gifting.GiftingUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.views.premium.GiftSelectView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout

class WidgetSettingsGifting : AppFragment(R.layout.widget_settings_gifting) {
    private lateinit var viewModel: SettingsGiftingViewModel
    private lateinit var flipper: AppViewFlipper
    private lateinit var input: TextInputLayout
    private lateinit var progress: ProgressBar
    private lateinit var gifts: WidgetSettingsGiftingAdapter
    private lateinit var promos: WidgetSettingsGiftingOutboundPromosAdapter

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.premium_gifting_title)
        setActionBarSubtitle(R.string.user_settings)
        viewModel = ViewModelProvider(this)[SettingsGiftingViewModel::class.java]
        flipper = view.findViewById(R.id.settings_gifting_flipper)
        input = view.findViewById(R.id.settings_gifting_gift_code_input_wrap)
        progress = view.findViewById(R.id.settings_gifting_gift_code_input_progress)
        gifts = WidgetSettingsGiftingAdapter(view.findViewById(R.id.settings_gifting_gifts_you_purchased_recycler))
        promos = WidgetSettingsGiftingOutboundPromosAdapter(viewModel::handlePromoMoreDetailsClicked, viewModel::handlePromoButtonClicked)
        view.findViewById<RecyclerView>(R.id.settings_gifting_gifts_you_purchased_recycler).adapter = gifts
        view.findViewById<RecyclerView>(R.id.settings_gifting_your_gifts_recycler).adapter = promos
        view.findViewById<View>(R.id.settings_gifting_retry).setOnClickListener { StoreStream.getEntitlements().fetchMyGiftEntitlements() }
        input.editText?.setOnEditorActionListener { field, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) false else {
                viewModel.redeemGiftCode(field.text.toString().removePrefix("https://discord.gift/").replace("-", ""), this)
                true
            }
        }
        configurePurchaseSection(view)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).appSubscribe(javaClass, onNext = ::render)
        viewModel.observeEvents().ui(this).appSubscribe(javaClass) { event ->
            when (event) {
                is SettingsGiftingViewModel.Event.ShowPromoBottomSheet -> WidgetOutboundPromoTerms.show(event.content, parentFragmentManager)
                is SettingsGiftingViewModel.Event.ShowPromoDialog -> WidgetClaimOutboundPromo.showAndRegisterForClaimResult(event.claimStatus, this, viewModel::handleClaimedPromo)
            }
        }
        viewModel.setOnGiftCodeResolved { code ->
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.gift_code_auth_accept)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.redeem) { _, _ ->
                    RestAPI.api.acceptGift(code).restSubscribeOn().ui(this).appSubscribe(javaClass) {
                        input.editText?.text?.clear()
                        StoreStream.getEntitlements().fetchMyGiftEntitlements()
                    }
                }
                .show()
        }
    }

    private fun render(state: SettingsGiftingViewModel.ViewState) {
        when (state) {
            SettingsGiftingViewModel.ViewState.Loading -> flipper.displayedChild = VIEW_LOADING
            SettingsGiftingViewModel.ViewState.Failure -> flipper.displayedChild = VIEW_FAILURE
            is SettingsGiftingViewModel.ViewState.Loaded -> {
                flipper.displayedChild = VIEW_LOADED
                val resolving = state.resolvingGiftState is SettingsGiftingViewModel.ResolvingGiftState.Resolving
                progress.visibility = if (resolving) View.VISIBLE else View.GONE
                input.isEnabled = !resolving
                input.error = if (state.resolvingGiftState is SettingsGiftingViewModel.ResolvingGiftState.Error) getString(R.string.application_entitlement_code_redemption_invalid) else null
                promos.submitList(state.outboundPromos)
                gifts.configure(
                    listItems(state),
                    viewModel::handleSkuClicked,
                    { code ->
                        (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("", GiftingUtils.generateGiftUrl(code)))
                        viewModel.handleCopyClicked(code)
                    },
                    { gift ->
                        RestAPI.api.revokeGiftCode(gift.code).restSubscribeOn().ui(this)
                            .appSubscribe(javaClass, onNext = { })
                    },
                    { skuId, planId ->
                        RestAPI.api.generateGiftCode(RestAPIParams.GenerateGiftCode(skuId, planId)).restSubscribeOn().ui(this)
                            .appSubscribe(javaClass, onNext = { StoreStream.getEntitlements().fetchMyGiftEntitlements() })
                    },
                )
            }
        }
    }

    private fun listItems(state: SettingsGiftingViewModel.ViewState.Loaded): List<WidgetSettingsGiftingAdapter.GiftItem> = buildList {
        if (state.myEntitlements.isEmpty()) add(WidgetSettingsGiftingAdapter.GiftItem(WidgetSettingsGiftingAdapter.GiftItem.TYPE_NO_GIFTS))
        state.myEntitlements.values.flatten().groupBy { it.subscriptionPlan?.id ?: it.skuId }.forEach { (key, entitlements) ->
            val first = entitlements.first()
            add(WidgetSettingsGiftingAdapter.GiftItem(WidgetSettingsGiftingAdapter.GiftItem.TYPE_SKU, expanded = key in state.expandedSkuOrPlanIds, sku = first.sku, copies = entitlements.size, planId = first.subscriptionPlan?.id))
            if (key in state.expandedSkuOrPlanIds) entitlements.forEachIndexed { index, entitlement ->
                val gift = state.myPurchasedGifts[key]?.getOrNull(index)
                add(WidgetSettingsGiftingAdapter.GiftItem(WidgetSettingsGiftingAdapter.GiftItem.TYPE_GIFT, gift, entitlement, true, planId = entitlement.subscriptionPlan?.id, isLastItem = index == entitlements.lastIndex, wasCopied = gift?.code == state.lastCopiedCode))
            }
        }
    }

    private fun configurePurchaseSection(view: View) {
        val section = view.findViewById<View>(R.id.settings_gifting_purchase_gift_section)
        if (!InAppSkuConfig.isInstalled) {
            section.visibility = View.GONE
            return
        }
        val details = (StoreStream.getGooglePlaySkuDetails().getState() as? StoreGooglePlaySkuDetails.State.Loaded)?.skuDetails.orEmpty()
        val views = listOf(
            R.id.gift_select_premium_tier_1_month,
            R.id.gift_select_premium_tier_1_year,
            R.id.gift_select_premium_tier_2_month,
            R.id.gift_select_premium_tier_2_year,
        ).map { id -> view.findViewById<GiftSelectView>(id) }
        premiumGifts.zip(views).forEach { (sku, giftView: GiftSelectView) ->
            sku.skuDetails = details[sku.paymentGatewaySkuId]
            giftView.configure(sku) { /* Billing manager intentionally absent until Manticord owns products. */ }
        }
    }

    companion object {
        private const val VIEW_LOADING = 0
        private const val VIEW_FAILURE = 1
        private const val VIEW_LOADED = 2
        fun launch(context: Context) = AppScreenRouter.launch(context, WidgetSettingsGifting::class.java)
    }
}
