package com.discord.views.premium

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.discord.R
import com.discord.utilities.billing.GooglePlayInAppSku
import com.discord.utilities.billing.premiumTier1Month
import com.discord.utilities.billing.premiumTier1Year
import com.discord.utilities.billing.premiumTier2Month
import com.discord.utilities.billing.premiumTier2Year
import com.google.android.material.card.MaterialCardView

class GiftSelectView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs, 0) {
    private val container: MaterialCardView
    private val icon: ImageView
    private val name: TextView
    private val currentPlan: TextView
    private val price: TextView
    private var onClickPlan: ((GooglePlayInAppSku) -> Unit)? = null
    private var inAppSku: GooglePlayInAppSku? = null

    init {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.widget_choose_plan_adapter_plan_item, this, false)
        addView(view)
        container = requireNotNull(view.findViewById(R.id.plan_item_container))
        icon = requireNotNull(view.findViewById(R.id.plan_item_icon))
        name = requireNotNull(view.findViewById(R.id.plan_item_name))
        currentPlan = requireNotNull(view.findViewById(R.id.plan_item_current_plan))
        price = requireNotNull(view.findViewById(R.id.plan_item_price))
    }

    fun configure(inAppSku: GooglePlayInAppSku, onClickPlan: (GooglePlayInAppSku) -> Unit) {
        this.inAppSku = inAppSku
        this.onClickPlan = onClickPlan
        val skuDetails = inAppSku.skuDetails ?: return

        name.text = skuDetails.description
        price.text = skuDetails.price
        currentPlan.visibility = View.GONE
        iconFor(inAppSku)?.let(icon::setImageResource)
        container.contentDescription = accessibilityLabel(
            skuDetails.description,
            skuDetails.price,
        )
        enable()
    }

    fun enable() {
        container.setOnClickListener {
            val sku = inAppSku
            if (sku != null) onClickPlan?.invoke(sku)
            container.setOnClickListener(null)
        }
    }

    internal companion object {
        fun iconFor(sku: GooglePlayInAppSku): Int? = when (sku) {
            premiumTier1Month -> R.drawable.ic_plan_premium_tier_1
            premiumTier1Year -> R.drawable.ic_plan_premium_tier_1_year
            premiumTier2Month -> R.drawable.ic_plan_premium_tier_2
            premiumTier2Year -> R.drawable.ic_plan_premium_tier_2_year
            else -> null
        }

        fun accessibilityLabel(description: String, price: String) = "$description, $price"
    }
}
