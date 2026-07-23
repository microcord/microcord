package com.discord.views.guildboost

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.discord.R
import com.discord.api.premium.PremiumTier
import com.google.android.material.button.MaterialButton
import java.text.NumberFormat

class GuildBoostMarketingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val tierOneContainer: LinearLayout
    private val learnMore: MaterialButton
    private val marketingContainer: LinearLayout
    private val subtitle: TextView
    private val title: TextView
    private val nitroBoostCount: TextView
    private val nitroBoostDiscount: TextView
    private val classicBoostCount: TextView
    private val classicBoostDiscount: TextView

    init {
        val content = LayoutInflater.from(context).inflate(R.layout.view_premium_marketing, this, false)
        addView(content)
        tierOneContainer = requireNotNull(content.findViewById(R.id.view_premium_marketing_container_tier1))
        learnMore = requireNotNull(content.findViewById(R.id.view_premium_marketing_learn_more))
        marketingContainer = content as LinearLayout
        subtitle = requireNotNull(content.findViewById(R.id.view_premium_marketing_marketing_subtitle))
        title = requireNotNull(content.findViewById(R.id.view_premium_marketing_marketing_title))
        nitroBoostCount = requireNotNull(content.findViewById(R.id.view_premium_marketing_nitro_boost_count))
        nitroBoostDiscount = requireNotNull(content.findViewById(R.id.view_premium_marketing_nitro_boost_discount))
        classicBoostCount = requireNotNull(content.findViewById(R.id.view_premium_marketing_nitro_classic_boost_count))
        classicBoostDiscount = requireNotNull(content.findViewById(R.id.view_premium_marketing_nitro_classic_boost_discount))
    }

    fun configure(userPremiumTier: PremiumTier, onLearnMoreClick: () -> Unit) {
        val boostCount = resources.getQuantityString(
            R.plurals.guild_settings_premium_upsell_heading_tertiary_premium_user_numFreeGuildSubscriptions,
            FREE_BOOST_COUNT,
        ).replace("{#}", FREE_BOOST_COUNT.toString())
        val discount = NumberFormat.getPercentInstance().format(BOOST_DISCOUNT)

        nitroBoostDiscount.text = context.getString(
            R.string.guild_settings_premium_upsell_body_perk_guild_subscription_discount,
        ).replace("{discountPercentage}", discount)
        nitroBoostCount.text = context.getString(
            R.string.guild_settings_premium_upsell_body_perk_num_guild_subscriptions,
        ).replace("{numFreeGuildSubscriptions}", boostCount)
        classicBoostDiscount.text = nitroBoostDiscount.text
        classicBoostCount.text = context.getString(
            R.string.guild_settings_premium_upsell_body_perk_no_free_guild_subscriptions,
        ).replace("{numFreeGuildSubscriptions}", boostCount)
        learnMore.setOnClickListener { onLearnMoreClick() }

        when (userPremiumTier) {
            PremiumTier.NONE, PremiumTier.TIER_0 -> subtitle.visibility = View.GONE
            PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY -> {
                marketingContainer.visibility = View.VISIBLE
                tierOneContainer.visibility = View.VISIBLE
                title.setText(R.string.guild_settings_premium_upsell_heading_secondary_premium_user)
                subtitle.visibility = View.GONE
            }
            PremiumTier.TIER_1 -> {
                marketingContainer.visibility = View.VISIBLE
                tierOneContainer.visibility = View.GONE
                title.setText(R.string.guild_settings_premium_upsell_heading_secondary_premium_user)
                subtitle.text = context.getString(
                    R.string.guild_settings_premium_upsell_heading_tertiary_premium_user,
                ).replace("{numFreeGuildSubscriptions}", FREE_BOOST_COUNT.toString())
                subtitle.visibility = View.VISIBLE
            }
            PremiumTier.TIER_2 -> marketingContainer.visibility = View.GONE
        }
    }

    private companion object {
        const val FREE_BOOST_COUNT = 2
        const val BOOST_DISCOUNT = 0.3
    }
}
