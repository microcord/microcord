package com.discord.widgets.guildscheduledevent

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

/**
 * The guild's event list.
 *
 * Every row is the same [GuildScheduledEventItemView]; the five callbacks are hoisted to the
 * adapter so a recycled card always dispatches against the item it is currently bound to.
 */
class GuildScheduledEventListAdapter(
    recyclerView: RecyclerView,
    private val onRsvpClicked: (GuildScheduledEventListItem.Event) -> Unit,
    private val onEventStartClicked: (GuildScheduledEventListItem.Event) -> Unit,
    private val onCardClicked: (GuildScheduledEventListItem.Event) -> Unit,
    private val onJoinClicked: (GuildScheduledEventListItem.Event) -> Unit,
    private val onShareClicked: (GuildScheduledEventListItem.Event) -> Unit,
) : MGRecyclerAdapterSimple<GuildScheduledEventListItem>(recyclerView) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, GuildScheduledEventListItem> = when (viewType) {
        GuildScheduledEventListItem.TYPE_EVENT -> EventViewHolder()
        else -> throw invalidViewTypeException(viewType)
    }

    inner class EventViewHolder :
        MGRecyclerViewHolder<GuildScheduledEventListAdapter, GuildScheduledEventListItem>(
            R.layout.guild_scheduled_event_list_item,
            this@GuildScheduledEventListAdapter,
        ) {
        private val card: GuildScheduledEventItemView =
            itemView.findViewById(R.id.guild_scheduled_event_item_view)

        override fun onConfigure(position: Int, data: GuildScheduledEventListItem) {
            super.onConfigure(position, data)
            val item = data as GuildScheduledEventListItem.Event
            card.configureInEventList(
                item = item,
                cardClickListener = { onCardClicked(item) },
                rsvpButtonClickListener = { onRsvpClicked(item) },
                eventStartButtonClickListener = { onEventStartClicked(item) },
                shareButtonClickListener = { onShareClicked(item) },
                joinButtonClickListener = { onJoinClicked(item) },
            )
        }
    }
}
