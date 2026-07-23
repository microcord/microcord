package com.discord.views.guildboost

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.discord.R
import kotlin.math.roundToInt

class GuildBoostProgressView(context: Context, attrs: AttributeSet?) :
    FrameLayout(context, attrs) {
    private val gemIcon: ImageView
    private val levelText: TextView
    private val progress: ProgressBar

    init {
        LayoutInflater.from(context).inflate(R.layout.view_premium_guild_progress, this, true)
        gemIcon = requireNotNull(findViewById(R.id.progress_gem_icon))
        levelText = requireNotNull(findViewById(R.id.progress_level_text))
        progress = requireNotNull(findViewById(R.id.progress_progress))
    }

    @Suppress("UNUSED_PARAMETER")
    fun configure(guildId: Long, premiumTier: Int, premiumSubscriptionCount: Int) {
        gemIcon.setImageResource(
            when (premiumTier) {
                0 -> themedResource(R.attr.boosted_guild_tier_0)
                1 -> R.drawable.ic_gem_tier_1
                2 -> R.drawable.ic_gem_tier_2
                3 -> R.drawable.ic_gem_tier_3
                else -> 0
            },
        )
        levelText.text = when (premiumTier) {
            0 -> context.getString(R.string.premium_guild_header_badge_no_tier)
            1 -> context.getString(R.string.premium_guild_tier_1)
            2 -> context.getString(R.string.premium_guild_tier_2)
            3 -> context.getString(R.string.premium_guild_tier_3)
            else -> ""
        }
        progress.progress = percentToNextTier(premiumTier, premiumSubscriptionCount)
    }

    private fun themedResource(attribute: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) value.resourceId else 0
    }

    internal companion object {
        private val BOOSTS_PER_TIER = intArrayOf(2, 7, 14)

        fun percentToNextTier(tier: Int, boostCount: Int): Int {
            if (tier >= BOOSTS_PER_TIER.size) return 100
            val currentTierBoosts = if (tier in 1..BOOSTS_PER_TIER.size) {
                BOOSTS_PER_TIER[tier - 1]
            } else {
                0
            }
            val nextTierBoosts = BOOSTS_PER_TIER.getOrElse(tier) { BOOSTS_PER_TIER[0] }
            return ((boostCount - currentTierBoosts).toFloat() / nextTierBoosts * 100).roundToInt()
        }
    }
}
