package com.discord.widgets.guildscheduledevent

import android.content.Context
import androidx.fragment.app.Fragment
import com.discord.api.channel.Channel
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.permission.Permission
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.models.guild.UserGuildMember
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuildScheduledEvents
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.StoreUserSettings
import com.discord.stores.StoreVoiceChannelSelected
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.guildscheduledevent.GuildScheduledEventUtilities
import com.discord.utilities.guildscheduledevent.getCreatorUserGuildMember
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import java.lang.ref.WeakReference
import rx.Observable

/**
 * Backs the event details sheet.
 *
 * Three pieces of state live here rather than in the store because they are the *sheet's*, not the
 * event's: which segment is selected, the derived [EventDetailsRsvpUsersFetchState], and the raw
 * segment index. They survive a store emission because [handleStoreState] folds them back into
 * every new view state.
 *
 * The attendee list is fetched lazily — only when the RSVP segment is first selected — because the
 * common case is opening the sheet, reading the description, and closing it again.
 */
class GuildScheduledEventDetailsViewModel(
    private val args: GuildScheduledEventDetailsArgs,
    private val guildScheduledEventsStore: StoreGuildScheduledEvents = StoreStream.getGuildScheduledEvents(),
    storeStateObservable: Observable<StoreState> = observeStores(args),
    private var section: EventDetailsSection = EventDetailsSection.EVENT_INFO,
    private var rsvpUsersFetchState: EventDetailsRsvpUsersFetchState = EventDetailsRsvpUsersFetchState.LOADING,
    private var segmentControlIndex: Int = 0,
) : AppViewModel<GuildScheduledEventDetailsViewModel.ViewState>() {

    data class StoreState(
        val guildScheduledEvent: GuildScheduledEvent? = null,
        val channel: Channel? = null,
        val guild: Guild? = null,
        val creator: UserGuildMember? = null,
        val isInGuild: Boolean = false,
        val isRsvped: Boolean = false,
        val canShare: Boolean = false,
        val canStartEvent: Boolean = false,
        val isDeveloperMode: Boolean = false,
        val selectedVoiceChannelId: Long? = null,
        val canConnect: Boolean = false,
        val rsvpUsers: List<GuildScheduledEventRsvpUserListItem.RsvpUser> = emptyList(),
        val isRsvpUsersFetching: Boolean = false,
        val isRsvpUsersError: Boolean = false,
    )

    sealed class ViewState {
        /**
         * The event could not be resolved — it was deleted, or the viewer lost access to it. The
         * sheet dismisses itself rather than showing an empty shell.
         */
        object Invalid : ViewState()

        data class Initialized(
            val guildScheduledEvent: GuildScheduledEvent,
            val locationInfo: GuildScheduledEventLocationInfo?,
            val guild: Guild,
            val channel: Channel?,
            val isInGuild: Boolean,
            val isRsvped: Boolean,
            val canShare: Boolean,
            val isConnected: Boolean,
            val canStartEvent: Boolean,
            val isDeveloperMode: Boolean,
            val canConnect: Boolean,
            val rsvpUsers: List<GuildScheduledEventRsvpUserListItem.RsvpUser>,
            val section: EventDetailsSection,
            val rsvpUsersFetchState: EventDetailsRsvpUsersFetchState,
            val segmentedControlIndex: Int,
            val creator: UserGuildMember?,
        ) : ViewState()
    }

    init {
        storeStateObservable
            .computationLatest()
            .ui(this)
            .appSubscribe(GuildScheduledEventDetailsViewModel::class.java, onNext = ::handleStoreState)
    }

    private fun handleStoreState(storeState: StoreState) {
        val event = storeState.guildScheduledEvent
        val guild = storeState.guild
        if (event == null || guild == null) {
            updateViewState(ViewState.Invalid)
            return
        }
        // First emission only: the store's cached counts may be stale or absent, and this is the
        // one screen where the attendee number is prominent.
        if (viewState == null) guildScheduledEventsStore.fetchGuildScheduledEventUserCounts(guild.id)

        // A directory-sourced event in a guild the viewer has not joined shows no location: they
        // could not enter the channel, and naming it leaks the guild's channel list. An external
        // event has no channel to leak, so it always shows its location.
        val locationInfo = if (
            args.source == GuildScheduledEventDetailsSource.Guild ||
            event.entityType == GuildScheduledEventEntityType.EXTERNAL ||
            storeState.isInGuild
        ) {
            GuildScheduledEventLocationInfo.buildLocationInfo(event, storeState.channel)
        } else {
            null
        }

        rsvpUsersFetchState = getRsvpUsersFetchState(storeState)
        updateViewState(
            ViewState.Initialized(
                guildScheduledEvent = event,
                locationInfo = locationInfo,
                guild = guild,
                channel = storeState.channel,
                isInGuild = storeState.isInGuild,
                isRsvped = storeState.isRsvped,
                canShare = storeState.canShare,
                isConnected = event.channelId != null &&
                    event.channelId == storeState.selectedVoiceChannelId,
                canStartEvent = storeState.canStartEvent,
                isDeveloperMode = storeState.isDeveloperMode,
                canConnect = storeState.canConnect,
                rsvpUsers = storeState.rsvpUsers,
                section = section,
                rsvpUsersFetchState = rsvpUsersFetchState,
                segmentedControlIndex = segmentControlIndex,
                creator = storeState.creator,
            ),
        )
    }

    /**
     * "Fetching" only counts as loading while nothing has arrived yet; a refresh over an existing
     * list keeps showing the list.
     */
    private fun getRsvpUsersFetchState(storeState: StoreState): EventDetailsRsvpUsersFetchState = when {
        storeState.isRsvpUsersFetching && storeState.rsvpUsers.isEmpty() -> EventDetailsRsvpUsersFetchState.LOADING
        storeState.isRsvpUsersError -> EventDetailsRsvpUsersFetchState.ERROR
        storeState.rsvpUsers.isEmpty() -> EventDetailsRsvpUsersFetchState.EMPTY
        else -> EventDetailsRsvpUsersFetchState.SUCCESS
    }

    // region actions

    fun onRsvpButtonClicked() {
        val state = viewState as? ViewState.Initialized ?: return
        guildScheduledEventsStore.toggleMeRsvpForEvent(state.guildScheduledEvent)
    }

    fun onGuildNameClicked() {
        val state = viewState as? ViewState.Initialized ?: return
        StoreStream.getGuildSelected().set(state.guild.id)
    }

    fun onShareButtonClicked(weakFragment: WeakReference<Fragment>) {
        val state = viewState as? ViewState.Initialized ?: return
        val event = state.guildScheduledEvent
        val canShare = GuildScheduledEventUtilities.canShareEvent(event.channelId, event.guildId)
        val fragment = weakFragment.get() ?: return
        GuildScheduledEventUtilities.launchInvite(canShare, fragment, event.guildId, state.channel, event.id)
    }

    fun endEventClicked(context: Context, onSuccess: () -> Unit) {
        val state = viewState as? ViewState.Initialized ?: return
        GuildScheduledEventAPI.endEvent(state.guildScheduledEvent.guildId, state.guildScheduledEvent.id)
            .restSubscribeOn()
            .appSubscribe(GuildScheduledEventDetailsViewModel::class.java, context) { onSuccess() }
    }

    fun onDeleteButtonClicked(context: Context, onSuccess: () -> Unit) {
        val state = viewState as? ViewState.Initialized ?: return
        RestAPI.api
            .deleteGuildScheduledEvent(state.guildScheduledEvent.guildId, state.guildScheduledEvent.id)
            .restSubscribeOn()
            .appSubscribe(GuildScheduledEventDetailsViewModel::class.java, context) { onSuccess() }
    }

    /**
     * Switching to the RSVP segment is what triggers the attendee fetch. The optimistic LOADING
     * flip avoids a frame of "no one is interested" before the request lands.
     */
    fun setSegmentedControlIndex(index: Int) {
        if (segmentControlIndex == index) return
        val state = viewState as? ViewState.Initialized ?: return
        segmentControlIndex = index
        section = if (index == 0) EventDetailsSection.EVENT_INFO else EventDetailsSection.RSVP_LIST
        if (section == EventDetailsSection.RSVP_LIST) {
            guildScheduledEventsStore.fetchGuildScheduledEventUsers(
                state.guildScheduledEvent.guildId,
                state.guildScheduledEvent.id,
            )
            if (state.rsvpUsers.isEmpty()) rsvpUsersFetchState = EventDetailsRsvpUsersFetchState.LOADING
        }
        updateViewState(
            state.copy(
                section = section,
                rsvpUsersFetchState = rsvpUsersFetchState,
                segmentedControlIndex = segmentControlIndex,
            ),
        )
    }

    // endregion

    companion object {
        /**
         * Resolves the event and everything the sheet needs to decide what to offer.
         *
         * **Degraded for [GuildScheduledEventDetailsSource.Directory].** The reference reads the
         * event out of `StoreDirectories.getGuildScheduledEventsForChannel(channelId)`, and takes
         * the guild from the event's own embedded `guild` field. `StoreDirectories` does not exist
         * in this target, so the directory branch falls back to the event store — which finds the
         * event only when it also arrived by some other route. The guild-side of the branch is
         * implemented, so restoring this is a one-expression change when that store lands.
         */
        fun observeStores(
            args: GuildScheduledEventDetailsArgs,
            observationDeck: ObservationDeck = ObservationDeckProvider.get(),
            guildScheduledEventsStore: StoreGuildScheduledEvents = StoreStream.getGuildScheduledEvents(),
            guildsStore: StoreGuilds = StoreStream.getGuilds(),
            userStore: StoreUser = StoreStream.getUsers(),
            channelsStore: StoreChannels = StoreStream.getChannels(),
            permissionsStore: StorePermissions = StoreStream.getPermissions(),
            userSettingsStore: StoreUserSettings = StoreStream.getUserSettings(),
            selectedVoiceChannelStore: StoreVoiceChannelSelected = StoreStream.getVoiceChannelSelected(),
        ): Observable<StoreState> = observationDeck.connectRx(
            guildScheduledEventsStore,
            guildsStore,
            userStore,
            channelsStore,
            permissionsStore,
            selectedVoiceChannelStore,
        ) {
            val event = guildScheduledEventsStore.findEventFromStore(args.eventId)
                ?: return@connectRx StoreState()

            val guild = when (args.source) {
                GuildScheduledEventDetailsSource.Guild -> guildsStore.getGuild(event.guildId)
                GuildScheduledEventDetailsSource.Directory ->
                    event.guild?.let { Guild(id = it.id, name = it.name, icon = it.icon) }
            }
            val isInGuild = guildsStore.getGuild(event.guildId) != null
            val creator = event.getCreatorUserGuildMember(guildsStore, userStore).takeIf { isInGuild }

            val rsvpUsers = guildScheduledEventsStore.getGuildScheduledEventUsers(event.id).values
                .map { GuildScheduledEventRsvpUserListItem.RsvpUser.from(it, event.guildId) }

            val selectedVoiceChannelId = selectedVoiceChannelStore.getSelectedVoiceChannelId()
            StoreState(
                guildScheduledEvent = event,
                channel = event.channelId?.let(channelsStore::getChannel),
                guild = guild,
                creator = creator,
                isInGuild = isInGuild,
                isRsvped = guildScheduledEventsStore.isMeRsvpedToEvent(event.guildId, args.eventId),
                canShare = GuildScheduledEventUtilities.canShareEvent(
                    event.channelId,
                    event.guildId,
                    channelsStore,
                    guildsStore,
                ),
                canStartEvent = GuildScheduledEventUtilities.canStartEvent(
                    event.guildId,
                    event.channelId,
                    channelsStore,
                    permissionsStore,
                ),
                isDeveloperMode = userSettingsStore.getIsDeveloperMode(),
                selectedVoiceChannelId = selectedVoiceChannelId.takeIf { it != 0L },
                canConnect = event.channelId?.let {
                    PermissionUtils.can(Permission.CONNECT, permissionsStore.getPermissionsByChannel()[it])
                } ?: false,
                rsvpUsers = rsvpUsers,
                isRsvpUsersFetching = guildScheduledEventsStore.getIsFetchingGuildScheduledEventUsers(),
                isRsvpUsersError = guildScheduledEventsStore.getIsGuildScheduledEventUsersError(),
            )
        }
    }
}
