package com.discord.widgets.hubs.events

import com.discord.api.directory.DirectoryEntryEvent
import com.discord.stores.utilities.RestCallState
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

sealed class HubEventsPage(override val type: Int) : MGRecyclerDataPayload {
    object Header : HubEventsPage(HEADER) { override val key = "header" }
    data class Event(val guildScheduledEventData: HubGuildScheduledEventData) : HubEventsPage(ENTRY) {
        override val key = guildScheduledEventData.event.id.toString()
    }
    data class Footer(val eventsAsync: RestCallState<List<DirectoryEntryEvent>>) : HubEventsPage(FOOTER) {
        override val key = "footer"
    }

    companion object {
        internal const val HEADER = 0
        internal const val FOOTER = 1
        internal const val ENTRY = 2
    }
}
