package com.discord.widgets.guildscheduledevent

import android.content.Context
import com.discord.api.channel.Channel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.permission.Permission
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.app.AppViewModel
import com.discord.models.guild.UserGuildMember
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuildScheduledEvents
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.stage.StageChannelAPI

class PreviewGuildScheduledEventViewModel(
    private val eventModel: GuildScheduledEventModel,
    private val existingEventData: WidgetPreviewGuildScheduledEvent.ExistingEventData?,
    channelsStore: StoreChannels = StoreStream.getChannels(),
    permissionsStore: StorePermissions = StoreStream.getPermissions(),
    private val guildScheduledEventsStore: StoreGuildScheduledEvents = StoreStream.getGuildScheduledEvents(),
) : AppViewModel<PreviewGuildScheduledEventViewModel.ViewState>(ViewState.Initial) {

    sealed class ViewState {
        object Initial : ViewState()
        object Invalid : ViewState()
        data class Initialized(
            val requestProcessing: Boolean,
            val existingEventAction: WidgetPreviewGuildScheduledEvent.Action?,
            val canNotifyEveryone: Boolean,
            val locationInfo: GuildScheduledEventLocationInfo,
            val eventModel: GuildScheduledEventModel,
        ) : ViewState() {
            val isStartingEvent: Boolean
                get() = existingEventAction == WidgetPreviewGuildScheduledEvent.Action.START_EVENT
        }
    }

    init {
        val channel = eventModel.channelId?.let(channelsStore::findChannelById)
        val canNotifyEveryone = existingEventData?.action == WidgetPreviewGuildScheduledEvent.Action.START_EVENT &&
            channel?.isStageVoiceChannel() == true &&
            PermissionUtils.can(Permission.MENTION_EVERYONE, permissionsStore.getPermissionsByChannel()[channel.id])
        updateViewState(
            ViewState.Initialized(
                requestProcessing = false,
                existingEventAction = existingEventData?.action,
                canNotifyEveryone = canNotifyEveryone,
                locationInfo = GuildScheduledEventLocationInfo.buildLocationInfo(eventModel, channel),
                eventModel = eventModel,
            ),
        )
    }

    fun isCreate(): Boolean = existingEventData == null

    fun onBottomButtonClicked(
        context: Context,
        sendNotification: Boolean = false,
        onRequestSuccess: (Long?) -> Unit,
    ) {
        val state = viewState as? ViewState.Initialized ?: return
        if (state.requestProcessing) return
        updateViewState(state.copy(requestProcessing = true))

        val request = when (existingEventData?.action) {
            null -> eventModel.toCreateRequestBody()?.let { body ->
                RestAPI.api.createGuildScheduledEvent(eventModel.guildId, body).map { it?.id }
            }
            WidgetPreviewGuildScheduledEvent.Action.EDIT_EVENT -> {
                val event = guildScheduledEventsStore.findEventFromStore(
                    existingEventData.guildScheduledEventId,
                    eventModel.guildId,
                )
                event?.let { current ->
                    eventModel.toUpdateRequestBody(current)?.let { body ->
                        RestAPI.api.updateGuildScheduledEvent(eventModel.guildId, current.id, body)
                            .map { current.id as Long? }
                    }
                }
            }
            WidgetPreviewGuildScheduledEvent.Action.START_EVENT -> {
                val eventId = existingEventData.guildScheduledEventId
                if (eventModel.entityType == GuildScheduledEventEntityType.STAGE_INSTANCE) {
                    val channelId = eventModel.channelId
                    val name = eventModel.name
                    if (channelId == null || name == null) null else StageChannelAPI.startStageInstance(
                        channelId,
                        name,
                        StageInstancePrivacyLevel.GUILD_ONLY,
                        sendNotification,
                        eventId.toString(),
                    ).map { eventId as Long? }
                } else {
                    GuildScheduledEventAPI.startEvent(eventModel.guildId, eventId).map { eventId as Long? }
                }
            }
        }

        if (request == null) {
            setRequestFinished()
            return
        }
        request.restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = PreviewGuildScheduledEventViewModel::class.java,
                context = context,
                errorHandler = { setRequestFinished() },
            ) { eventId ->
                setRequestFinished()
                onRequestSuccess(eventId)
            }
    }

    private fun setRequestFinished() {
        val state = viewState as? ViewState.Initialized ?: return
        updateViewState(state.copy(requestProcessing = false))
    }

    companion object {
        fun fallbackCreator(): UserGuildMember = UserGuildMember(StoreStream.getUsers().getMe())
    }
}
