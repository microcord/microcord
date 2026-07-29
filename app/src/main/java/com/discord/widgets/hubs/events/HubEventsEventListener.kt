package com.discord.widgets.hubs.events

interface HubEventsEventListener {
    fun dismissHeader()
    fun fetchGuildScheduledEvents()
    fun onCardClicked(eventData: HubGuildScheduledEventData)
    fun onPrimaryButtonClicked(eventData: HubGuildScheduledEventData)
    fun onSecondaryButtonClicked(eventData: HubGuildScheduledEventData)
    fun onShareClicked(eventData: HubGuildScheduledEventData)
}
