package com.discord.widgets.servers.guild_role_subscription

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.widgets.stickers.StickerPackFormatting
import com.discord.views.VitoImageView

sealed class TierViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    class AddTierItemViewHolder(
        itemView: View,
        onClick: () -> Unit,
    ) : TierViewHolder(itemView) {
        init {
            itemView.findViewById<TextView>(R.id.guild_role_subscription_add_tier_text)
                .setText(R.string.guild_settings_role_subscription_tier_card_create)
            itemView.setOnClickListener { onClick() }
        }
    }

    class TierItemViewHolder(itemView: View) : TierViewHolder(itemView) {
        fun configureUI(
            tier: ServerSettingsGuildRoleSubscriptionTierAdapterItem.Tier,
            onClick: (Long) -> Unit,
        ) {
            itemView.setOnClickListener { onClick(tier.tierListingId) }
            itemView.findViewById<TextView>(R.id.guild_role_subscription_tier_name).text = tier.tierName
            itemView.findViewById<TextView>(R.id.guild_role_subscription_tier_price).text =
                StickerPackFormatting.formattedPriceUsd(tier.tierPrice, itemView.context)
            itemView.findViewById<View>(R.id.guild_role_subscription_tier_draft_tag).visibility =
                if (tier.isPublished) View.GONE else View.VISIBLE
            val image = itemView.findViewById<VitoImageView>(R.id.guild_role_subscription_tier_icon)
            val url = tier.tierImageAssetId?.let {
                IconUtils.getStoreAssetImage(tier.applicationId, it.toString(), MAX_TIER_IMAGE_SIZE)
            }
            MGImages.setImage(image, url)
        }
    }

    private companion object {
        const val MAX_TIER_IMAGE_SIZE = 64
    }
}
