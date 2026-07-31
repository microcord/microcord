package com.discord.widgets.guildscheduledevent

import com.discord.api.channel.Channel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.channel.isVoiceChannel
import com.discord.api.guild.GuildFeature
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuildScheduledEvents
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.utilities.guildscheduledevent.GuildScheduledEventUtilities
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable

class WidgetGuildScheduledEventLocationSelectViewModel(
    private val guildId: Long,
    private val initialChannelId: Long?,
    private val existingGuildScheduledEventId: Long?,
    private val channelsStore: StoreChannels = StoreStream.getChannels(),
    guildsStore: StoreGuilds = StoreStream.getGuilds(),
    permissionsStore: StorePermissions = StoreStream.getPermissions(),
    guildScheduledEventsStore: StoreGuildScheduledEvents = StoreStream.getGuildScheduledEvents(),
    storeStateObservable: Observable<StoreState> = observeStores(
        guildId,
        existingGuildScheduledEventId,
        guildsStore,
        channelsStore,
        permissionsStore,
        guildScheduledEventsStore,
    ),
) : AppViewModel<WidgetGuildScheduledEventLocationSelectViewModel.ViewState>() {

    data class StoreState(
        val guild: Guild?,
        val channels: Map<Long, Channel>,
        val channelPermissions: Map<Long, Long>,
        val guildPermissions: Long?,
        val existingGuildScheduledEvent: GuildScheduledEvent?,
    )

    sealed class ViewState {
        object Invalid : ViewState()
        data class Valid(
            val selectedLocationOption: GuildScheduledEventEntityType,
            val selectedChannel: Channel?,
            val externalLocation: String,
            val availableChannels: Map<Long, Channel>,
            val availableVoiceChannelIds: Set<Long>,
            val availableStageChannelIds: Set<Long>,
            val canCreateExternalEvent: Boolean,
            val showStageOptionIfUnavailable: Boolean,
            val canChangeChannel: Boolean,
        ) : ViewState()
    }

    internal var currentStoreState: StoreState? = null
        private set

    init {
        storeStateObservable.computationLatest().ui(this)
            .appSubscribe(WidgetGuildScheduledEventLocationSelectViewModel::class.java, onNext = ::handleStoreState)
    }

    internal fun handleStoreState(storeState: StoreState) {
        currentStoreState = storeState
        updateViewState(
            reduce(
                initialChannelId,
                existingGuildScheduledEventId,
                storeState,
                viewState as? ViewState.Valid,
            ),
        )
    }

    fun selectChannel(channelId: Long) {
        val state = viewState as? ViewState.Valid ?: return
        val channel = currentStoreState?.channels?.get(channelId) ?: return
        updateViewState(state.copy(selectedChannel = channel))
    }

    fun selectLocationOption(locationOption: GuildScheduledEventEntityType) {
        val state = viewState as? ViewState.Valid ?: return
        if (state.selectedLocationOption == locationOption) return
        val initialChannel = initialChannelId?.let { currentStoreState?.channels?.get(it) }
        val selected = when {
            locationOption == GuildScheduledEventEntityType.VOICE && initialChannel?.isVoiceChannel() == true -> initialChannel
            locationOption == GuildScheduledEventEntityType.STAGE_INSTANCE && initialChannel?.isStageVoiceChannel() == true -> initialChannel
            locationOption == GuildScheduledEventEntityType.VOICE ->
                state.availableChannels.values.firstOrNull(Channel::isVoiceChannel)
            locationOption == GuildScheduledEventEntityType.STAGE_INSTANCE ->
                state.availableChannels.values.firstOrNull(Channel::isStageVoiceChannel)
            else -> null
        }
        updateViewState(
            state.copy(
                selectedLocationOption = locationOption,
                selectedChannel = selected,
                externalLocation = "",
            ),
        )
    }

    fun setExternalLocation(location: String) {
        val state = viewState as? ViewState.Valid ?: return
        updateViewState(state.copy(externalLocation = location))
    }

    companion object {
        internal fun reduce(
            initialChannelId: Long?,
            existingGuildScheduledEventId: Long?,
            storeState: StoreState,
            current: ViewState.Valid? = null,
        ): ViewState.Valid {
            val canCreateExternalEvent = GuildScheduledEventUtilities.canCreateExternalEvent(storeState.guildPermissions)
            val availableChannels = storeState.channels.filterValues { channel ->
                GuildScheduledEventUtilities.canStartEventInChannel(
                    channel,
                    storeState.channelPermissions[channel.id],
                )
            }
            val availableVoiceChannelIds = HashSet<Long>()
            val availableStageChannelIds = HashSet<Long>()
            availableChannels.values.forEach { channel ->
                when {
                    channel.isVoiceChannel() -> availableVoiceChannelIds += channel.id
                    channel.isStageVoiceChannel() -> availableStageChannelIds += channel.id
                }
            }

            val isEditing = existingGuildScheduledEventId != null
            val selectedChannelId = current?.selectedChannel?.id
                ?: if (isEditing) storeState.existingGuildScheduledEvent?.channelId else initialChannelId
            val selectedChannel = availableChannels[selectedChannelId]
            val requestedType = current?.selectedLocationOption
                ?: storeState.existingGuildScheduledEvent?.entityType
                ?: getEntityTypeForChannel(selectedChannel)
            val selectedLocationOption = when (requestedType) {
                GuildScheduledEventEntityType.STAGE_INSTANCE ->
                    requestedType.takeUnless { availableStageChannelIds.isEmpty() }
                        ?: GuildScheduledEventEntityType.NONE
                GuildScheduledEventEntityType.VOICE ->
                    requestedType.takeUnless { availableVoiceChannelIds.isEmpty() }
                        ?: GuildScheduledEventEntityType.NONE
                GuildScheduledEventEntityType.EXTERNAL ->
                    requestedType.takeIf { canCreateExternalEvent } ?: GuildScheduledEventEntityType.NONE
                else -> requestedType
            }
            val externalLocation = current?.externalLocation
                ?: if (isEditing && selectedChannelId == null) {
                    storeState.existingGuildScheduledEvent?.entityMetadata?.location
                } else null

            return ViewState.Valid(
                selectedLocationOption = selectedLocationOption,
                selectedChannel = selectedChannel,
                externalLocation = externalLocation.orEmpty(),
                availableChannels = availableChannels,
                availableVoiceChannelIds = availableVoiceChannelIds,
                availableStageChannelIds = availableStageChannelIds,
                canCreateExternalEvent = canCreateExternalEvent,
                showStageOptionIfUnavailable = storeState.guild?.features?.contains(GuildFeature.COMMUNITY) == true,
                canChangeChannel = !isEditing ||
                    storeState.existingGuildScheduledEvent?.let(ScheduledEventUi::timing) != GuildScheduledEventTiming.LIVE,
            )
        }

        private fun getEntityTypeForChannel(channel: Channel?): GuildScheduledEventEntityType = when {
            channel?.isVoiceChannel() == true -> GuildScheduledEventEntityType.VOICE
            channel?.isStageVoiceChannel() == true -> GuildScheduledEventEntityType.STAGE_INSTANCE
            else -> GuildScheduledEventEntityType.NONE
        }

        private fun observeStores(
            guildId: Long,
            existingGuildScheduledEventId: Long?,
            guildsStore: StoreGuilds,
            channelsStore: StoreChannels,
            permissionsStore: StorePermissions,
            guildScheduledEventsStore: StoreGuildScheduledEvents,
        ): Observable<StoreState> = Observable.combineLatest(
            guildsStore.observeGuild(guildId),
            channelsStore.observeChannelsForGuild(guildId),
            permissionsStore.observeChannelPermissionsForGuild(guildId),
            permissionsStore.observePermissionsForGuild(guildId),
            guildScheduledEventsStore.observeGuildScheduledEvent(existingGuildScheduledEventId, guildId),
        ) { guild, channels, channelPermissions, guildPermissions, existingEvent ->
            StoreState(guild, channels, channelPermissions, guildPermissions, existingEvent)
        }
    }
}
