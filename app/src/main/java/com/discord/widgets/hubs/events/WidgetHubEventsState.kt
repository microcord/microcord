package com.discord.widgets.hubs.events

import com.discord.api.directory.DirectoryEntryEvent
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.RestCallState

data class WidgetHubEventsState(
    val showHeader: Boolean = true,
    val eventsData: List<HubGuildScheduledEventData> = emptyList(),
    val eventsAsync: RestCallState<List<DirectoryEntryEvent>> = Default,
)
