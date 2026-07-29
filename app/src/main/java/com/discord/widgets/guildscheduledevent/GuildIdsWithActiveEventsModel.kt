package com.discord.widgets.guildscheduledevent

import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.stores.StoreGuildScheduledEvents
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

object GuildIdsWithActiveEventsModel {
    @JvmOverloads
    fun observe(
        storeGuildScheduledEvents: StoreGuildScheduledEvents = StoreStream.getGuildScheduledEvents(),
        observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    ): Observable<Set<Long>> = observationDeck.connectRx(storeGuildScheduledEvents) {
        activeGuildIds(storeGuildScheduledEvents.getAllGuildScheduledEvents())
    }

    internal fun activeGuildIds(
        eventsByGuild: Map<Long, List<com.discord.api.guildscheduledevent.GuildScheduledEvent>>,
    ): Set<Long> = eventsByGuild
        .filterValues { events -> events.any { it.status == GuildScheduledEventStatus.ACTIVE } }
        .keys
}
