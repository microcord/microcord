package com.discord.widgets.guild_role_subscriptions

import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.widgets.stickers.StickerPackFormatting

/** One selectable price point, rendered as a formatted USD amount. */
class PriceTierViewHolder(private val label: TextView) : RecyclerView.ViewHolder(label) {

    fun configureUI(priceTier: Int, onItemClickListener: (Int) -> Unit) {
        // Reference: `utilities/billing/PremiumUtilsKt.getFormattedPriceUsd`. That file does not
        // exist here; `StickerPackFormatting` holds the same reconstruction and is documented as
        // the temporary home. Both call sites move together when it lands.
        label.text = StickerPackFormatting.formattedPriceUsd(priceTier, label.context)
        label.setOnClickListener { onItemClickListener(priceTier) }
    }
}
