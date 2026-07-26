package com.discord.widgets.hubs.events

import android.view.View
import com.discord.R
import com.discord.models.guild.Guild
import com.discord.widgets.guildscheduledevent.GuildScheduledEventItemView
import com.discord.widgets.guildscheduledevent.buttonconfiguration.DirectoryButtonConfiguration

/**
 * One scheduled event card. Binds `R.layout.widget_hub_event`, whose root *is* the shared
 * [GuildScheduledEventItemView].
 *
 * The reference calls that view's `configureInDirectoryEventList`, which this project's copy does not
 * declare (and which this wave does not own). The same [DirectoryButtonConfiguration] is built here
 * and handed to the public `configure`, which is what `configureInDirectoryEventList` does.
 */
class WidgetHubEventViewHolder(
    adapter: WidgetHubEventsPageAdapter,
    private val listener: HubEventsEventListener,
) : WidgetHubEventsPageViewHolder(R.layout.widget_hub_event, adapter) {

    private val itemViewCard: GuildScheduledEventItemView = itemView as GuildScheduledEventItemView

    override fun onConfigure(position: Int, data: HubEventsPage) {
        super.onConfigure(position, data)
        val eventData = (data as? HubEventsPage.Event)?.guildScheduledEventData ?: return
        val event = eventData.event

        itemViewCard.configure(
            event = event,
            // The reference passes no location info for directory cards, so the card shows the guild
            // rather than the channel. Passing a null channel produces the same thing here.
            channel = null,
            guild = event.guild?.let { apiGuild ->
                // `models.guild.Guild` has no api-Guild constructor in this project. The card only
                // reads id, name and icon (shortName is derived), so those are supplied.
                Guild(id = apiGuild.id, name = apiGuild.name, icon = apiGuild.icon)
            },
            creator = null,
            buttonConfiguration = DirectoryButtonConfiguration(
                guildScheduledEvent = event,
                isInGuild = eventData.isInGuild,
                isRsvped = eventData.isRsvped,
                isDetailView = false,
                primaryButtonOnClickListener = View.OnClickListener { listener.onPrimaryButtonClicked(eventData) },
                secondaryButtonOnClickListener = View.OnClickListener { listener.onSecondaryButtonClicked(eventData) },
                shareButtonOnClickListener = View.OnClickListener { listener.onShareClicked(eventData) },
                extrasButtonOnClickListener = View.OnClickListener { },
            ),
            onCardClicked = View.OnClickListener { listener.onCardClicked(eventData) },
        )
    }
}
