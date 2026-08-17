package com.discord.widgets.settings.premium

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.i18n.FormatUtils
import com.discord.utilities.icon.IconUtils
import com.discord.views.VitoImageView
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import java.util.Date

class WidgetSettingsGiftingOutboundPromosAdapter(
    private val onMoreDetailsClick: (SettingsGiftingViewModel.OutboundPromoItem) -> Unit,
    private val onButtonClick: (SettingsGiftingViewModel.OutboundPromoItem) -> Unit,
) : ListAdapter<SettingsGiftingViewModel.OutboundPromoItem, WidgetSettingsGiftingOutboundPromosAdapter.OutboundPromoViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = OutboundPromoViewHolder(
        parent,
        onMoreDetailsClick,
        onButtonClick,
    )

    override fun onBindViewHolder(holder: OutboundPromoViewHolder, position: Int) = holder.bindTo(getItem(position))

    class OutboundPromoViewHolder(
        parent: ViewGroup,
        private val onMoreDetailsClick: (SettingsGiftingViewModel.OutboundPromoItem) -> Unit,
        private val onButtonClick: (SettingsGiftingViewModel.OutboundPromoItem) -> Unit,
    ) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.view_gift_outbound_promo_list_item, parent, false),
    ) {
        private val button: MaterialButton = itemView.findViewById(R.id.giftPromoButton)
        private val description: TextView = itemView.findViewById(R.id.giftPromoDescription)
        private val image: VitoImageView = itemView.findViewById(R.id.giftPromoImage)
        private val moreDetails: TextView = itemView.findViewById(R.id.giftPromoMoreDetails)
        private val title: TextView = itemView.findViewById(R.id.giftPromoTitle)
        private lateinit var item: SettingsGiftingViewModel.OutboundPromoItem

        init {
            moreDetails.setOnClickListener { onMoreDetailsClick(item) }
            button.setOnClickListener { onButtonClick(item) }
        }

        fun bindTo(item: SettingsGiftingViewModel.OutboundPromoItem) {
            this.item = item
            IconUtils.setIcon(image, item.imageUrl)
            title.text = item.title
            val (date, descriptionRes, buttonRes) = when (val status = item.claimStatus) {
                is ClaimStatus.Unclaimed -> Triple(status.claimBy.dateTimeMillis, R.string.mobile_outbound_promotion_card_unclaimed_body, R.string.gift_embed_button_claim)
                is ClaimStatus.Claimed -> Triple(status.redeemBy.dateTimeMillis, R.string.mobile_outbound_promotion_card_claimed_body, R.string.outbound_promotion_see_code)
            }
            val renderedDate = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(date))
            description.text = FormatUtils.format(itemView.context.getString(descriptionRes), "endDate" to renderedDate)
            button.setText(buttonRes)
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SettingsGiftingViewModel.OutboundPromoItem>() {
            override fun areItemsTheSame(oldItem: SettingsGiftingViewModel.OutboundPromoItem, newItem: SettingsGiftingViewModel.OutboundPromoItem) =
                oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: SettingsGiftingViewModel.OutboundPromoItem, newItem: SettingsGiftingViewModel.OutboundPromoItem) =
                oldItem == newItem
        }
    }
}
