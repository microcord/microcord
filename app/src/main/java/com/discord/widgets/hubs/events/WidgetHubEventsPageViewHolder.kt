package com.discord.widgets.hubs.events

import android.view.LayoutInflater
import android.view.View
import androidx.annotation.LayoutRes
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

sealed class WidgetHubEventsPageViewHolder(
    itemView: View,
    adapter: WidgetHubEventsPageAdapter,
) : MGRecyclerViewHolder<WidgetHubEventsPageAdapter, HubEventsPage>(itemView, adapter) {
    constructor(@LayoutRes layout: Int, adapter: WidgetHubEventsPageAdapter) : this(
        LayoutInflater.from(adapter.recycler.context).inflate(layout, adapter.recycler, false),
        adapter,
    )
}
