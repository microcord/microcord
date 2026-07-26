package com.discord.widgets.hubs

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.mg_recycler.SingleTypePayload
import com.discord.views.IconRow

/** The rows of [WidgetRadioSelectorBottomSheet]. */
class RadioSelectorAdapter(
    recycler: RecyclerView,
    private val onSelected: (Int) -> Unit,
) : MGRecyclerAdapterSimple<SingleTypePayload<RadioSelectorItem>>(recycler) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, SingleTypePayload<RadioSelectorItem>> = RadioSelectorViewHolder(this, onSelected)

    fun setItems(items: List<RadioSelectorItem>) {
        // The key carries `selected` so re-selecting a row is diffed as a change, not skipped.
        setData(items.map { SingleTypePayload(it, "${it.id}:${it.selected}") })
    }
}

/**
 * One selectable option. Binds `R.layout.widget_radio_selector_view_holder`, whose root *is* the
 * [IconRow]; the check mark is the row's icon rather than a separate radio button.
 */
class RadioSelectorViewHolder(
    adapter: RadioSelectorAdapter,
    private val onSelected: (Int) -> Unit,
) : MGRecyclerViewHolder<RadioSelectorAdapter, SingleTypePayload<RadioSelectorItem>>(
    R.layout.widget_radio_selector_view_holder,
    adapter,
) {
    private val row: IconRow = itemView.findViewById(R.id.icon_row)

    override fun onConfigure(position: Int, data: SingleTypePayload<RadioSelectorItem>) {
        super.onConfigure(position, data)
        val item = data.data
        row.setText(item.text)
        row.setImageRes(if (item.selected) R.drawable.ic_check_brand_16dp else 0)
        row.setOnClickListener { onSelected(item.id) }
    }
}
