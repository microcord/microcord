package com.discord.widgets.settings.premium

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.stores.StoreGooglePlayPurchases
import com.discord.utilities.billing.GooglePlaySku
import com.discord.utilities.dimmer.DimmerView

/**
 * The plan picker presentation from 126.21.
 *
 * Selection is returned as a fragment result. Launching Play checkout remains the billing owner's
 * responsibility; this screen never claims that a purchase started without that integration.
 */
class WidgetChoosePlan : AppFragment(R.layout.widget_choose_plan) {
    private lateinit var adapter: WidgetChoosePlanAdapter
    private lateinit var emptyContainer: LinearLayout
    private lateinit var dimmer: DimmerView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val recycler = view.findViewById<RecyclerView>(R.id.choose_plan_recycler)
        emptyContainer = view.findViewById(R.id.choose_plan_empty_container)
        dimmer = view.findViewById(R.id.dimmer_view)
        adapter = WidgetChoosePlanAdapter(recycler, ::returnSelection)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
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

    private fun returnSelection(
        sku: GooglePlaySku,
        oldSkuName: String?,
        @Suppress("UNUSED_PARAMETER") upgradeSkuDetails: com.android.billingclient.api.SkuDetails?,
    ) {
        parentFragmentManager.setFragmentResult(
            RESULT_PLAN_SELECTED,
            Bundle().apply {
                putString(RESULT_SKU_NAME, sku.skuName)
                putString(RESULT_OLD_SKU_NAME, oldSkuName)
            },
        )
    }

    enum class ViewType {
        BUY_PREMIUM_TIER_2,
        BUY_PREMIUM_TIER_1,
        BUY_PREMIUM_GUILD,
        SWITCH_PLANS,
    }

    companion object {
        const val RESULT_PLAN_SELECTED = "choose_plan_selected"
        const val RESULT_SKU_NAME = "result_sku_name"
        const val RESULT_OLD_SKU_NAME = "result_extra_current_sku_name"
    }
}
