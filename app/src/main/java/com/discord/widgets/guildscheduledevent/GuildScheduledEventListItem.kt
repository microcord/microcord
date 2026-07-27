package com.discord.widgets.guildscheduledevent

import com.discord.api.channel.Channel
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.models.guild.UserGuildMember
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

/**
 * A row of the guild's event list.
 *
 * Every per-viewer decision the card makes is resolved once, in the view model, and carried here:
 * the card itself never touches a store, so the same row type serves the list sheet and the diff.
 */
sealed class GuildScheduledEventListItem : MGRecyclerDataPayload {

    data class Event(
        val event: GuildScheduledEvent,
        val channel: Channel?,
        val creator: UserGuildMember?,
        val isRsvped: Boolean,
        val canStartEvent: Boolean,
        val canShare: Boolean,
        /** The viewer is already in the event's voice/stage channel. */
        val isConnected: Boolean,
        val canConnect: Boolean,
    ) : GuildScheduledEventListItem() {
        override val type: Int = TYPE_EVENT
        override val key: String = event.id.toString()
    }

    companion object {
        const val TYPE_EVENT: Int = 0
    }
}
