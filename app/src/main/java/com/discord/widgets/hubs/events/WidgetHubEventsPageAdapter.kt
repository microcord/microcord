package com.discord.widgets.hubs.events

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

/**
 * Header + events + footer, in that order.
 *
 * The reference rebuilds the whole list with `notifyDataSetChanged`; this project diffs it like every
 * other list, which is why [HubEventsPage] carries a stable key per row.
 */
class WidgetHubEventsPageAdapter(
    recycler: RecyclerView,
    private val eventListener: HubEventsEventListener,
) : MGRecyclerAdapterSimple<HubEventsPage>(recycler) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, HubEventsPage> = when (viewType) {
        HubEventsPage.HEADER -> WidgetHubEventsPageHeaderViewHolder(this, eventListener)
        HubEventsPage.FOOTER -> WidgetHubEventsPageFooterViewHolder(this, eventListener)
        HubEventsPage.ENTRY -> WidgetHubEventViewHolder(this, eventListener)
        else -> throw invalidViewTypeException(viewType)
    }

    fun setItems(items: List<HubEventsPage>) = setData(items)
}
