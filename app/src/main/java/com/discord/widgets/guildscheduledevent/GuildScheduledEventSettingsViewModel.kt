package com.discord.widgets.guildscheduledevent

import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityMetadata
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.app.AppViewModel
import com.discord.stores.StoreGuildScheduledEvents
import com.discord.stores.StoreStream
import com.discord.utilities.time.ClockFactory

class GuildScheduledEventSettingsViewModel(
    guildId: Long,
    existingEventId: Long?,
    entityType: GuildScheduledEventEntityType,
    channelId: Long?,
    externalLocation: String?,
    guildScheduledEventsStore: StoreGuildScheduledEvents = StoreStream.getGuildScheduledEvents(),
) : AppViewModel<GuildScheduledEventSettingsViewModel.ViewState>() {

    enum class DateError { INVALID_VIEW_STATE, START_DATE_IN_PAST, END_DATE_IN_PAST, END_DATE_BEFORE_START_DATE }

    sealed class ViewState {
        object Invalid : ViewState()
        data class Initialized(
            val eventModel: GuildScheduledEventModel,
            val existingEvent: GuildScheduledEvent?,
        ) : ViewState()
    }

    init {
        val existing = existingEventId?.let { guildScheduledEventsStore.findEventFromStore(it, guildId) }
        val eventModel = existing?.toModel()?.copy(
            channelId = channelId,
            entityType = entityType,
            entityMetadata = metadata(entityType, externalLocation),
        ) ?: GuildScheduledEventPickerDateTime.generateAppropriateStartDateTime().let { (date, time) ->
            GuildScheduledEventModel(
                guildId = guildId,
                name = null,
                channelId = channelId,
                creatorId = null,
                startDate = date,
                startTime = time,
                endDate = null,
                endTime = null,
                description = null,
                entityType = entityType,
                entityMetadata = metadata(entityType, externalLocation),
                userCount = 1,
            )
        }
        updateViewState(ViewState.Initialized(eventModel, existing))
    }

    fun setTopic(topic: String) = updateModel { it.copy(name = topic) }
    fun setDescription(description: String) = updateModel { it.copy(description = description) }
    fun toggleBroadcastToDirectoryChannel(enabled: Boolean) =
        updateModel { it.copy(broadcastToDirectoryChannels = enabled) }
    fun setStartDate(year: Int, month: Int, day: Int): DateError? =
        updateModel { it.copy(startDate = GuildScheduledEventPickerDate(year, month, day)) }
    fun setStartTime(hour: Int, minute: Int): DateError? =
        updateModel { it.copy(startTime = GuildScheduledEventPickerTime(hour, minute, 0)) }
    fun setEndDate(year: Int, month: Int, day: Int): DateError? =
        updateModel { it.copy(endDate = GuildScheduledEventPickerDate(year, month, day)) }
    fun setEndTime(hour: Int, minute: Int): DateError? =
        updateModel { it.copy(endTime = GuildScheduledEventPickerTime(hour, minute, 0)) }

    fun isNextButtonEnabled(): Boolean {
        val model = (viewState as? ViewState.Initialized)?.eventModel ?: return false
        return !model.name.isNullOrEmpty() &&
            (model.entityType != GuildScheduledEventEntityType.EXTERNAL ||
                (model.endDate != null && model.endTime != null))
    }

    fun hasStartTimeChanged(state: ViewState.Initialized): Boolean = state.existingEvent == null ||
        GuildScheduledEventPickerDateTime.toMillis(state.eventModel.startDate, state.eventModel.startTime) !=
        state.existingEvent.scheduledStartTime.dateTimeMillis

    fun hasEndTimeChanged(state: ViewState.Initialized): Boolean {
        val existing = state.existingEvent ?: return true
        val model = state.eventModel
        val current = if (model.endDate != null && model.endTime != null) {
            GuildScheduledEventPickerDateTime.toMillis(model.endDate, model.endTime)
        } else null
        return current != existing.scheduledEndTime?.dateTimeMillis
    }

    fun isDateInFuture(date: GuildScheduledEventPickerDate?, time: GuildScheduledEventPickerTime?): Boolean =
        date == null || time == null ||
            GuildScheduledEventPickerDateTime.toMillis(date, time) >= ClockFactory.get().currentTimeMillis()

    fun isStartDateBeforeEndDate(state: ViewState.Initialized): Boolean {
        val model = state.eventModel
        val endDate = model.endDate ?: return true
        val endTime = model.endTime ?: return endDate.toMillis() >= model.startDate.toMillis()
        return GuildScheduledEventPickerDateTime.toMillis(endDate, endTime) >
            GuildScheduledEventPickerDateTime.toMillis(model.startDate, model.startTime)
    }

    private fun updateModel(transform: (GuildScheduledEventModel) -> GuildScheduledEventModel): DateError? {
        val state = viewState as? ViewState.Initialized ?: return DateError.INVALID_VIEW_STATE
        updateViewState(state.copy(eventModel = transform(state.eventModel)))
        return null
    }

    private fun metadata(type: GuildScheduledEventEntityType, location: String?) =
        if (type == GuildScheduledEventEntityType.EXTERNAL) GuildScheduledEventEntityMetadata(location) else null
}
