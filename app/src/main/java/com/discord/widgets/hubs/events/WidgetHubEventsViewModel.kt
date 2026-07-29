package com.discord.widgets.hubs.events

import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.permission.Permission
import com.discord.app.AppViewModel
import com.discord.stores.StoreChannels
import com.discord.stores.StoreDirectories
import com.discord.stores.StoreGuildScheduledEvents
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreVoiceChannelSelected
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.stores.utilities.Default
import com.discord.utilities.guildscheduledevent.GuildScheduledEventUtilities
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.widgets.hubs.storeDirectories
import rx.Observable

/**
 * Loads the scheduled events published into one hub directory channel.
 *
 * The fetch is kicked off once on construction and can be repeated from the footer button, so the
 * page always has a loading and a retry path rather than an indefinitely empty list.
 */
class WidgetHubEventsViewModel(
    private val guildId: Long,
    private val directoryChannelId: Long,
    private val directoriesStore: StoreDirectories = storeDirectories(),
    private val guildScheduledEventsStore: StoreGuildScheduledEvents = StoreStream.getGuildScheduledEvents(),
    guildsStore: StoreGuilds = StoreStream.getGuilds(),
    channelsStore: StoreChannels = StoreStream.getChannels(),
    permissionsStore: StorePermissions = StoreStream.getPermissions(),
    voiceChannelSelection: StoreVoiceChannelSelected = StoreStream.getVoiceChannelSelected(),
    observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : AppViewModel<WidgetHubEventsState>(WidgetHubEventsState()) {

    init {
        observeStores(
            observationDeck,
            directoriesStore,
            guildsStore,
            channelsStore,
            voiceChannelSelection,
            guildScheduledEventsStore,
            permissionsStore,
            directoryChannelId,
        )
            .computationLatest()
            .ui(this)
            .appSubscribe(WidgetHubEventsViewModel::class.java) { updateViewState(it) }

        fetchGuildScheduledEvents()
    }

    fun dismissHeader() {
        viewState?.let { updateViewState(it.copy(showHeader = false)) }
        directoriesStore.markGuildScheduledEventsHeaderDismissed()
    }

    fun fetchGuildScheduledEvents() {
        directoriesStore.fetchGuildScheduledEventsForChannel(guildId, directoryChannelId)
    }

    /** Drops a finished fetch so the footer stops showing its outcome. */
    fun reset() {
        updateViewState(requireViewState().copy(eventsAsync = Default))
    }

    fun toggleRsvp(event: GuildScheduledEvent) {
        guildScheduledEventsStore.toggleMeRsvpForEvent(event)
    }

    private fun observeStores(
        observationDeck: ObservationDeck,
        directoriesStore: StoreDirectories,
        guildsStore: StoreGuilds,
        channelsStore: StoreChannels,
        voiceChannelSelection: StoreVoiceChannelSelected,
        guildScheduledEventsStore: StoreGuildScheduledEvents,
        permissionsStore: StorePermissions,
        directoryChannelId: Long,
    ): Observable<WidgetHubEventsState> = observationDeck.connectRx(
        directoriesStore,
        guildsStore,
        channelsStore,
        voiceChannelSelection,
        guildScheduledEventsStore,
        permissionsStore,
    ).map {
        val eventsAsync = directoriesStore.getGuildScheduledEventsForChannel(directoryChannelId)
        if (eventsAsync == null) {
            // Nothing has ever been requested for this channel: keep whatever is on screen.
            return@map viewState
        }

        val channelPermissions = permissionsStore.getPermissionsByChannel()
        val connectedChannelId = voiceChannelSelection.getSelectedVoiceChannelId().takeIf { id -> id != 0L }

        val eventsData = eventsAsync()?.map { entry ->
            val event = entry.guildScheduledEvent
            val eventChannelId = event.channelId
            HubGuildScheduledEventData(
                directoryChannelId = directoryChannelId,
                event = event,
                channel = eventChannelId?.let(channelsStore::getChannel),
                isRsvped = event.id in guildScheduledEventsStore.getMeGuildScheduledEventIds(event.guildId),
                canShare = GuildScheduledEventUtilities.canShareEvent(eventChannelId, event.guildId),
                canStartEvent = GuildScheduledEventUtilities.canStartEvent(event.guildId, eventChannelId),
                canConnect = PermissionUtils.can(
                    Permission.CONNECT,
                    eventChannelId?.let { id -> channelPermissions[id] },
                ),
                isConnected = connectedChannelId != null && connectedChannelId == eventChannelId,
                isInGuild = guildsStore.getGuild(event.guildId) != null,
            )
        // An in-flight or failed fetch must not blank a list that is already on screen.
        } ?: viewState?.eventsData.orEmpty()

        WidgetHubEventsState(
            showHeader = !directoriesStore.getGuildScheduledEventsHeaderDismissed(),
            eventsData = eventsData,
            eventsAsync = eventsAsync,
        )
    }.filter { it != null }.map { it!! }
}
