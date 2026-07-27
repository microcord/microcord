package com.discord.widgets.guildscheduledevent

import com.discord.api.channel.Channel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.channel.isVoiceChannel
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.app.AppViewModel
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuildScheduledEvents
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.utilities.guildscheduledevent.GuildScheduledEventUtilities
import com.discord.utilities.rx.appSubscribe
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
) : AppViewModel<WidgetGuildScheduledEventLocationSelectViewModel.ViewState>() {

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

    init {
        Observable.combineLatest(
            guildsStore.observeGuild(guildId),
            channelsStore.observeChannelsForGuild(guildId),
            permissionsStore.observeChannelPermissionsForGuild(guildId),
            permissionsStore.observePermissionsForGuild(guildId),
            guildScheduledEventsStore.observeGuildScheduledEvent(existingGuildScheduledEventId, guildId),
        ) { guild, channels, channelPermissions, guildPermissions, existingEvent ->
            if (guild == null) return@combineLatest ViewState.Invalid
            val availableChannels = channels.filterValues { channel ->
                GuildScheduledEventUtilities.canStartEventInChannel(channel, channelPermissions[channel.id])
            }
            val voiceIds = availableChannels.values.filter(Channel::isVoiceChannel).mapTo(linkedSetOf(), Channel::id)
            val stageIds = availableChannels.values.filter(Channel::isStageVoiceChannel).mapTo(linkedSetOf(), Channel::id)
            val initialChannel = (existingEvent?.channelId ?: initialChannelId)?.let(channels::get)
            val type = existingEvent?.entityType ?: when {
                initialChannel?.isStageVoiceChannel() == true -> GuildScheduledEventEntityType.STAGE_INSTANCE
                initialChannel?.isVoiceChannel() == true -> GuildScheduledEventEntityType.VOICE
                stageIds.isNotEmpty() -> GuildScheduledEventEntityType.STAGE_INSTANCE
                voiceIds.isNotEmpty() -> GuildScheduledEventEntityType.VOICE
                else -> GuildScheduledEventEntityType.EXTERNAL
            }
            val selectedChannel = initialChannel ?: when (type) {
                GuildScheduledEventEntityType.STAGE_INSTANCE -> stageIds.firstOrNull()?.let(channels::get)
                GuildScheduledEventEntityType.VOICE -> voiceIds.firstOrNull()?.let(channels::get)
                else -> null
            }
            ViewState.Valid(
                selectedLocationOption = type,
                selectedChannel = selectedChannel,
                externalLocation = existingEvent?.entityMetadata?.location.orEmpty(),
                availableChannels = availableChannels,
                availableVoiceChannelIds = voiceIds,
                availableStageChannelIds = stageIds,
                canCreateExternalEvent = GuildScheduledEventUtilities.canCreateExternalEvent(guildPermissions),
                showStageOptionIfUnavailable = existingEvent?.entityType == GuildScheduledEventEntityType.STAGE_INSTANCE,
                canChangeChannel = existingEvent?.status != GuildScheduledEventStatus.ACTIVE,
            )
        }
            .ui(this)
            .appSubscribe(WidgetGuildScheduledEventLocationSelectViewModel::class.java) { updateViewState(it) }
    }

    fun selectChannel(channelId: Long) {
        val state = viewState as? ViewState.Valid ?: return
        val channel = state.availableChannels[channelId] ?: return
        updateViewState(state.copy(selectedChannel = channel))
    }

    fun selectLocationOption(locationOption: GuildScheduledEventEntityType) {
        val state = viewState as? ViewState.Valid ?: return
        if (state.selectedLocationOption == locationOption) return
        val selected = when (locationOption) {
            GuildScheduledEventEntityType.STAGE_INSTANCE -> state.availableStageChannelIds.firstOrNull()
            GuildScheduledEventEntityType.VOICE -> state.availableVoiceChannelIds.firstOrNull()
            else -> null
        }?.let(state.availableChannels::get)
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
}
