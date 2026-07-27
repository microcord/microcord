package com.discord.widgets.guild_role_subscriptions

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R

/** The list of price points inside [WidgetPriceTierPickerBottomSheet]. */
class PriceTierPickerAdapter(
    private val itemClickListener: (Int) -> Unit,
) : RecyclerView.Adapter<PriceTierViewHolder>() {

    private var items: List<Int> = emptyList()

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PriceTierViewHolder(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.dialog_simple_selector_item, parent, false) as TextView,
    )

    override fun onBindViewHolder(holder: PriceTierViewHolder, position: Int) {
        holder.configureUI(items[position], itemClickListener)
    }

    /**
     * The catalogue arrives once and whole; there is no incremental update to diff against, so the
     * reference rebinds everything and so does this.
     */
    fun setItems(newItems: List<Int>) {
        items = newItems
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }
}
