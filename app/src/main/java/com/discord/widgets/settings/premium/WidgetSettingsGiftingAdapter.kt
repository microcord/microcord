package com.discord.widgets.settings.premium

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppViewFlipper
import com.discord.i18n.FormatUtils
import com.discord.models.domain.ModelEntitlement
import com.discord.models.domain.ModelGift
import com.discord.models.domain.ModelSku
import com.discord.utilities.gifting.GiftingUtils
import com.discord.utilities.premium.PremiumUtils
import com.discord.utilities.view.text.LinkifiedTextView
import com.google.android.material.button.MaterialButton

class WidgetSettingsGiftingAdapter(
    @Suppress("UNUSED_PARAMETER") recycler: RecyclerView,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    data class GiftItem(
        val typeInt: Int,
        val gift: ModelGift? = null,
        val entitlement: ModelEntitlement? = null,
        val expanded: Boolean? = null,
        val sku: ModelSku? = null,
        val copies: Int? = null,
        val planId: Long? = null,
        val isLastItem: Boolean? = null,
        val wasCopied: Boolean? = null,
    ) {
        val key: String = when (typeInt) {
            TYPE_SKU -> sku?.id.toString()
            TYPE_GIFT -> entitlement?.id.toString()
            else -> ""
        }

        companion object {
            const val TYPE_NO_GIFTS = 0
            const val TYPE_SKU = 1
            const val TYPE_GIFT = 2
        }
    }

    private var items = emptyList<GiftItem>()
    private var onClickSku: (Long, Long?) -> Unit = { _, _ -> }
    private var onClickCopy: (String) -> Unit = {}
    private var onRevoke: (ModelGift) -> Unit = {}
    private var onGenerate: (Long, Long?) -> Unit = { _, _ -> }

    fun configure(
        giftItems: List<GiftItem>,
        onClickSkuListener: (Long, Long?) -> Unit,
        onClickCopyListener: (String) -> Unit,
        onRevokeClickListener: (ModelGift) -> Unit,
        onGenerateClickListener: (Long, Long?) -> Unit,
    ) {
        items = giftItems
        onClickSku = onClickSkuListener
        onClickCopy = onClickCopyListener
        onRevoke = onRevokeClickListener
        onGenerate = onGenerateClickListener
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = items[position].typeInt

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            GiftItem.TYPE_NO_GIFTS -> NoGiftsListItem(inflater.inflate(R.layout.view_no_gifts_list_item, parent, false))
            GiftItem.TYPE_SKU -> SkuListItem(inflater.inflate(R.layout.view_gift_sku_list_item, parent, false))
            GiftItem.TYPE_GIFT -> EntitlementListItem(inflater.inflate(R.layout.view_gift_entitlement_list_item, parent, false))
            else -> error("Invalid gifting item type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SkuListItem -> holder.bind(items[position])
            is EntitlementListItem -> holder.bind(items[position])
        }
    }

    class NoGiftsListItem(itemView: View) : RecyclerView.ViewHolder(itemView)

    inner class SkuListItem(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val arrow: ImageView = itemView.findViewById(R.id.gift_sku_arrow)
        private val copies: TextView = itemView.findViewById(R.id.gift_sku_copies)
        private val icon: com.facebook.drawee.view.SimpleDraweeView = itemView.findViewById(R.id.gift_sku_icon)
        private val name: TextView = itemView.findViewById(R.id.gift_sku_name)

        fun bind(item: GiftItem) {
            val sku = requireNotNull(item.sku)
            name.text = sku.name
            val count = item.copies ?: 0
            copies.text = FormatUtils.format(
                itemView.resources.getQuantityString(R.plurals.gift_inventory_copies_copies, count),
                "copies" to count,
            )
            arrow.setImageResource(R.drawable.icon_carrot)
            arrow.rotation = if (item.expanded == true) 180f else 0f
            if (PremiumUtils.isNitroSku(sku)) icon.setImageResource(PremiumUtils.getNitroGiftIcon(sku))
            else icon.setImageURI(sku.application?.icon?.let { com.discord.utilities.icon.IconUtils.getApplicationIcon(sku.applicationId, it) })
            itemView.setOnClickListener { onClickSku(sku.id, item.planId) }
        }
    }

    inner class EntitlementListItem(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val code: TextView = itemView.findViewById(R.id.gift_entitlement_code)
        private val copy: MaterialButton = itemView.findViewById(R.id.gift_entitlement_copy)
        private val divider: View = itemView.findViewById(R.id.gift_entitlement_divider)
        private val flipper: AppViewFlipper = itemView.findViewById(R.id.gift_entitlement_flipper)
        private val generate: MaterialButton = itemView.findViewById(R.id.gift_entitlement_generate)
        private val revoke: LinkifiedTextView = itemView.findViewById(R.id.gift_entitlement_revoke)

        fun bind(item: GiftItem) {
            itemView.layoutParams.height = if (item.expanded == false) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
            val entitlement = item.entitlement ?: return
            val gift = item.gift
            divider.visibility = if (item.isLastItem == false) View.VISIBLE else View.GONE
            if (gift == null) {
                flipper.displayedChild = 1
                generate.setOnClickListener { onGenerate(entitlement.skuId, entitlement.subscriptionPlan?.id) }
                return
            }
            flipper.displayedChild = 0
            code.text = GiftingUtils.generateGiftUrl(gift.code)
            copy.setText(if (item.wasCopied == true) R.string.copied else R.string.copy)
            copy.setOnClickListener { onClickCopy(gift.code) }
            revoke.text = itemView.context.getString(R.string.revoke)
            revoke.setOnClickListener { onRevoke(gift) }
        }
    }
}
