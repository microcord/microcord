package com.discord.widgets.hubs.events

import android.widget.ImageView
import com.discord.R

/**
 * "What are Hub Events?" explainer card. Binds `R.layout.widget_hub_events_header`.
 *
 * Dismissible; the dismissal is persisted by the directories store so it does not come back.
 */
class WidgetHubEventsPageHeaderViewHolder(
    adapter: WidgetHubEventsPageAdapter,
    private val listener: HubEventsEventListener,
) : WidgetHubEventsPageViewHolder(R.layout.widget_hub_events_header, adapter) {

    private val close: ImageView = itemView.findViewById(R.id.close)

    override fun onConfigure(position: Int, data: HubEventsPage) {
        super.onConfigure(position, data)
        close.setOnClickListener { listener.dismissHeader() }
    }
}
