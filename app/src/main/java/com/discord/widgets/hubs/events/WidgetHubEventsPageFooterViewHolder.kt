package com.discord.widgets.hubs.events

import com.discord.R
import com.discord.stores.utilities.Loading
import com.discord.views.LoadingButton

/**
 * The always-present tail of the list. Binds `R.layout.widget_hub_events_footer`.
 *
 * It carries the fetch state, so it is simultaneously the empty state ("no events scheduled"), the
 * loading indicator, and the retry button after a failure.
 */
class WidgetHubEventsPageFooterViewHolder(
    adapter: WidgetHubEventsPageAdapter,
    private val listener: HubEventsEventListener,
) : WidgetHubEventsPageViewHolder(R.layout.widget_hub_events_footer, adapter) {

    private val button: LoadingButton = itemView.findViewById(R.id.button)

    override fun onConfigure(position: Int, data: HubEventsPage) {
        super.onConfigure(position, data)
        val footer = data as? HubEventsPage.Footer ?: return
        button.setOnClickListener { listener.fetchGuildScheduledEvents() }
        button.setIsLoading(footer.eventsAsync is Loading)
    }
}
