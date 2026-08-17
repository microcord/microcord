package com.discord.views.guildboost

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.discord.R
import com.discord.api.guild.GuildFeature
import com.discord.models.guild.Guild
import com.discord.views.VitoImageView
import com.google.android.material.chip.Chip

class GuildBoostConfirmationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : CardView(context, attrs) {
    private val avatar: VitoImageView
    private val guildName: TextView
    private val previousTier: Chip
    private val newTier: Chip
    private val tierArrow: ImageView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_guild_boost_confirmation, this)
        avatar = requireNotNull(findViewById(R.id.guild_boost_confirmation_avatar))
        guildName = requireNotNull(findViewById(R.id.guild_boost_confirmation_guild_name))
        previousTier = requireNotNull(findViewById(R.id.guild_boost_confirmation_level_info_previous))
        newTier = requireNotNull(findViewById(R.id.guild_boost_confirmation_level_info_upgrade))
        tierArrow = requireNotNull(findViewById(R.id.guild_boost_confirmation_upgrade_arrow))
    }

    fun configure(guild: Guild, netNewSubscriptions: Int) {
        val resultingTier = boostTier(guild, guild.premiumSubscriptionCount + netNewSubscriptions)
        val change = resultingTier.compareTo(guild.premiumTier)

        avatar.setImageURI(guild.icon?.let { hash ->
            val extension = if (hash.startsWith("a_")) "gif" else "png"
            "https://cdn.discordapp.com/icons/${guild.id}/$hash.$extension"
        })
        guildName.text = guild.name
        previousTier.setChipIconResource(tierIcon(guild.premiumTier))
        previousTier.text = tierName(guild.premiumTier)

        val changed = change != 0
        tierArrow.visibility = if (changed) View.VISIBLE else View.GONE
        newTier.visibility = if (changed) View.VISIBLE else View.GONE
        if (!changed) return

        val tint = ContextCompat.getColor(
            context,
            if (change < 0) R.color.status_red_500 else R.color.brand_500,
        )
        tierArrow.drawable?.let { DrawableCompat.setTint(it.mutate(), tint) }
        newTier.setChipIconResource(tierIcon(resultingTier))
        newTier.text = tierName(resultingTier)
    }

    private fun tierIcon(tier: Int) = when (tier) {
        0 -> themedResource(R.attr.boosted_guild_tier_0)
        1 -> themedResource(R.attr.guild_boost_badge_tier_1)
        2 -> themedResource(R.attr.guild_boost_badge_tier_2)
        3 -> themedResource(R.attr.guild_boost_badge_tier_3)
        else -> 0
    }

    private fun tierName(tier: Int) = when (tier) {
        0 -> context.getString(R.string.premium_guild_header_badge_no_tier)
        1 -> context.getString(R.string.premium_guild_tier_1)
        2 -> context.getString(R.string.premium_guild_tier_2)
        3 -> context.getString(R.string.premium_guild_tier_3)
        else -> ""
    }

    private fun themedResource(attribute: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) value.resourceId else 0
    }

    internal companion object {
        fun boostTier(guild: Guild, boostCount: Int): Int {
            val thresholds = when {
                guild.hasFeature(GuildFeature.BOOSTING_TIERS_EXPERIMENT_SMALL_GUILD) -> intArrayOf(2, 3, 4)
                guild.hasFeature(GuildFeature.BOOSTING_TIERS_EXPERIMENT_MEDIUM_GUILD) -> intArrayOf(2, 7, 10)
                else -> intArrayOf(2, 7, 14)
            }
            return thresholds.indexOfLast { boostCount >= it } + 1
        }
    }
}
