package com.discord.widgets.guildscheduledevent

import android.content.Context
import androidx.fragment.app.Fragment
import com.discord.api.channel.Channel
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.permission.Permission
import com.discord.app.AppViewModel
import com.discord.models.guild.UserGuildMember
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuildScheduledEvents
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.StoreVoiceChannelSelected
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.guildscheduledevent.GuildScheduledEventUtilities
import com.discord.utilities.guildscheduledevent.GuildScheduledEventsComparator
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import java.lang.ref.WeakReference
import rx.Observable

/**
 * Backs the guild's event list sheet.
 *
 * Constructing it kicks off the three refreshes the list needs — real attendee counts, the
 * viewer's own RSVPs, and the read acknowledgement that clears the sidebar's "new event" dot —
 * because opening the sheet is exactly the moment all three become worth paying for.
 */
class GuildScheduledEventListViewModel(
    private val guildId: Long,
    private val channelId: Long? = null,
    private val guildScheduledEventsStore: StoreGuildScheduledEvents = StoreStream.getGuildScheduledEvents(),
    private val channelsStore: StoreChannels = StoreStream.getChannels(),
    storeStateObservable: Observable<StoreState> = observeStores(guildId, channelId),
) : AppViewModel<GuildScheduledEventListViewModel.ViewState>(ViewState.Initial) {

    data class StoreState(
        val guildScheduledEvents: List<GuildScheduledEvent>,
        val userGuildScheduledEventIds: Set<Long>,
        val guildChannels: Map<Long, Channel>,
        val creators: Map<Long, UserGuildMember>,
        val canCreateEvents: Boolean,
        val selectedVoiceChannelId: Long?,
        val channelPermissions: Map<Long, Long>,
    )

    sealed class ViewState {
        object Initial : ViewState()

        data class Loaded(
            val guildScheduledEvents: List<GuildScheduledEventListItem.Event>,
            val canCreateEvents: Boolean,
        ) : ViewState()
    }

    init {
        guildScheduledEventsStore.fetchGuildScheduledEventUserCounts(guildId)
        guildScheduledEventsStore.fetchMeGuildScheduledEvents(guildId)
        guildScheduledEventsStore.ackGuildEvents(guildId)
        storeStateObservable
            .computationLatest()
            .ui(this)
            .appSubscribe(GuildScheduledEventListViewModel::class.java, onNext = ::handleStoreState)
    }

    private fun handleStoreState(storeState: StoreState) {
        // Reported once per sheet opening, not once per store emission.
        if (viewState !is ViewState.Loaded) {
            AnalyticsTracker.openGuildScheduledEventSheet(guildId, storeState.guildScheduledEvents.size)
        }
        val rows = storeState.guildScheduledEvents.map { event ->
            val channel = event.channelId?.let(storeState.guildChannels::get)
            GuildScheduledEventListItem.Event(
                event = event,
                channel = channel,
                creator = event.creatorId?.let(storeState.creators::get),
                isRsvped = event.id in storeState.userGuildScheduledEventIds,
                canStartEvent = GuildScheduledEventUtilities.canStartEvent(guildId, channel?.id),
                canShare = GuildScheduledEventUtilities.canShareEvent(channel?.id, guildId),
                isConnected = event.channelId != null &&
                    event.channelId == storeState.selectedVoiceChannelId,
                canConnect = event.channelId?.let {
                    PermissionUtils.can(Permission.CONNECT, storeState.channelPermissions[it])
                } ?: false,
            )
        }
        updateViewState(ViewState.Loaded(rows, storeState.canCreateEvents))
    }

    fun toggleRsvp(guildScheduledEvent: GuildScheduledEvent) =
        guildScheduledEventsStore.toggleMeRsvpForEvent(guildScheduledEvent)

    /**
     * Sharing needs the guild's default channel, which is only known asynchronously, so the invite
     * path resolves it first. Without share permission there is nothing to wait for and the public
     * event URL is used directly.
     */
    fun onShareClicked(
        guildScheduledEvent: GuildScheduledEvent,
        weakContext: WeakReference<Context>,
        weakFragment: WeakReference<Fragment>,
    ) {
        val guildId = guildScheduledEvent.guildId
        val eventId = guildScheduledEvent.id
        val channelId = guildScheduledEvent.channelId
        val canShare = GuildScheduledEventUtilities.canShareEvent(channelId, guildId)
        val channel = channelId?.let(channelsStore::getChannel)
        if (canShare) {
            StoreStream.getChannels().observeDefaultChannel(guildId)
                .first()
                .appSubscribe(GuildScheduledEventListViewModel::class.java) { defaultChannel ->
                    val fragment = weakFragment.get() ?: return@appSubscribe
                    GuildScheduledEventUtilities.launchInvite(
                        canShare = true,
                        fragment = fragment,
                        guildId = guildId,
                        channel = channel ?: defaultChannel,
                        guildEventId = eventId,
                    )
                }
            return
        }
        val context = weakContext.get() ?: return
        com.discord.app.AppToast.copyToClipboard(
            context,
            GuildScheduledEventUrlUtils.getEventDetailsUrl(guildId, eventId),
        )
    }

    companion object {
        /**
         * @param channelId when non-null, restricts the list to events hosted in that one channel
         *   — the entry point from a voice channel rather than from the guild header.
         */
        fun observeStores(
            guildId: Long,
            channelId: Long?,
            observationDeck: ObservationDeck = ObservationDeckProvider.get(),
            guildScheduledEventsStore: StoreGuildScheduledEvents = StoreStream.getGuildScheduledEvents(),
            channelsStore: StoreChannels = StoreStream.getChannels(),
            permissionsStore: StorePermissions = StoreStream.getPermissions(),
            guildsStore: StoreGuilds = StoreStream.getGuilds(),
            voiceChannelSelectedStore: StoreVoiceChannelSelected = StoreStream.getVoiceChannelSelected(),
            userStore: StoreUser = StoreStream.getUsers(),
        ): Observable<StoreState> = observationDeck.connectRx(
            guildScheduledEventsStore,
            channelsStore,
            permissionsStore,
            guildsStore,
            voiceChannelSelectedStore,
            userStore,
        ) {
            val guildChannels = channelsStore.getChannelsForGuild(guildId)
            val events = guildScheduledEventsStore.getGuildScheduledEvents(guildId)
                .let { all -> if (channelId != null) all.filter { it.channelId == channelId } else all }
                .sortedWith(GuildScheduledEventsComparator)

            val creatorIds = events.mapNotNull { it.creatorId }.toSet()
            // `fetchMissing = true`: a creator who has left the guild is not in the member cache,
            // and the card would otherwise show an unnamed avatar forever.
            val users = userStore.getUsers(creatorIds, true)
            val creators = creatorIds.mapNotNull { userId ->
                users[userId]?.let { userId to UserGuildMember(it, guildsStore.getMember(guildId, userId)) }
            }.toMap()

            val selectedVoiceChannelId = voiceChannelSelectedStore.getSelectedVoiceChannelId()
            StoreState(
                guildScheduledEvents = events,
                userGuildScheduledEventIds = guildScheduledEventsStore.getMeGuildScheduledEventIds(guildId),
                guildChannels = guildChannels,
                creators = creators,
                canCreateEvents = GuildScheduledEventUtilities.canCreateAnyEvent(
                    guildId,
                    channelsStore,
                    permissionsStore,
                ),
                selectedVoiceChannelId = selectedVoiceChannelId.takeIf { it != 0L },
                channelPermissions = permissionsStore.getPermissionsByChannel(),
            )
        }
    }
}
