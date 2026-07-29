package com.discord.widgets.settings.premium

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.android.billingclient.api.SkuDetails
import com.discord.R
import com.discord.api.premium.SubscriptionInterval
import com.discord.utilities.billing.GooglePlaySku
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.google.android.material.card.MaterialCardView

class WidgetChoosePlanAdapter(
    recycler: RecyclerView,
    private val onClickPlan: (GooglePlaySku, String?, SkuDetails?) -> Unit,
) : MGRecyclerAdapterSimple<WidgetChoosePlanAdapter.Item>(recycler, false) {

    sealed interface Item : MGRecyclerDataPayload {
        data class Header(val titleStringResId: Int) : Item {
            override val key = "0"
            override val type = TYPE_HEADER
        }

        data class Plan(
            val displaySku: GooglePlaySku,
            val skuDetails: SkuDetails,
            val purchaseSku: GooglePlaySku?,
            val oldSkuName: String? = null,
            val upgradeSkuDetails: SkuDetails? = null,
            val isCurrentPlan: Boolean = false,
        ) : Item {
            override val key = "1${displaySku.skuName}"
            override val type = TYPE_PLAN
        }

        data object Divider : Item {
            override val key = "2"
            override val type = TYPE_DIVIDER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MGRecyclerViewHolder<*, Item> =
        when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(this)
            TYPE_PLAN -> PlanViewHolder(this)
            TYPE_DIVIDER -> DividerViewHolder(this)
            else -> throw invalidViewTypeException(viewType)
        }

    private class HeaderViewHolder(
        adapter: WidgetChoosePlanAdapter,
    ) : MGRecyclerViewHolder<WidgetChoosePlanAdapter, Item>(R.layout.widget_choose_plan_adapter_header_item, adapter) {
        override fun onConfigure(position: Int, data: Item) {
            itemView.findViewById<TextView>(R.id.select_subscription_header_text)
                .setText((data as Item.Header).titleStringResId)
        }
    }

    private class PlanViewHolder(
        adapter: WidgetChoosePlanAdapter,
    ) : MGRecyclerViewHolder<WidgetChoosePlanAdapter, Item>(R.layout.widget_choose_plan_adapter_plan_item, adapter) {
        override fun onConfigure(position: Int, data: Item) {
            val plan = data as Item.Plan
            itemView.findViewById<TextView>(R.id.plan_item_name).text = plan.skuDetails.description
            itemView.findViewById<ImageView>(R.id.plan_item_icon).setImageResource(plan.displaySku.iconDrawableResId)
            itemView.findViewById<ImageView>(R.id.plan_item_border).setImageResource(
                if (plan.isCurrentPlan) GooglePlaySku.getBorderResource(plan.displaySku.type) else 0,
            )
            itemView.findViewById<TextView>(R.id.plan_item_current_plan).visibility =
                if (plan.isCurrentPlan) View.VISIBLE else View.GONE
            itemView.findViewById<TextView>(R.id.plan_item_price).apply {
                text = context.getString(
                    if (plan.displaySku.interval == SubscriptionInterval.MONTHLY) {
                        R.string.billing_price_per_month
                    } else {
                        R.string.billing_price_per_year
                    },
                ).replace("!!{price}!!", plan.skuDetails.price)
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    0,
                    0,
                    if (plan.purchaseSku == null) 0 else itemView.getThemedDrawableRes(R.attr.ic_navigate_next),
                    0,
                )
            }
            itemView.findViewById<MaterialCardView>(R.id.plan_item_container).apply {
                isClickable = plan.purchaseSku != null
                setOnClickListener(
                    plan.purchaseSku?.let { purchaseSku ->
                        View.OnClickListener {
                            adapter.onClickPlan(purchaseSku, plan.oldSkuName, plan.upgradeSkuDetails)
                        }
                    },
                )
            }
        }
    }

    private class DividerViewHolder(
        adapter: WidgetChoosePlanAdapter,
    ) : MGRecyclerViewHolder<WidgetChoosePlanAdapter, Item>(R.layout.widget_choose_plan_adapter_divider_item, adapter)

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_PLAN = 1
        const val TYPE_DIVIDER = 2
    }
}
