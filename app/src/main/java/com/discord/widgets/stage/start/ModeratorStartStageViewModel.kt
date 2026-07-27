package com.discord.widgets.stage.start

import androidx.annotation.MainThread
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.api.permission.Permission
import com.discord.app.AppViewModel
import com.discord.models.guild.UserGuildMember
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuildScheduledEvents
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreVoiceChannelSelected
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.filterNull
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.rx.ui
import com.discord.widgets.guildscheduledevent.ScheduledEventUi
import java.util.concurrent.TimeUnit
import rx.Observable

/**
 * Builds the moderator's start-stage menu.
 *
 * The list is assembled in a fixed order: a header, then — only if the moderator may run events —
 * the one scheduled event that is close enough to start, then "go live now", then "schedule an
 * event", then who is already waiting in the audience.
 */
class ModeratorStartStageViewModel(
    private val channelId: Long,
    private val selectedVoiceChannelStore: StoreVoiceChannelSelected =
        StoreStream.getVoiceChannelSelected(),
    channelsStore: StoreChannels = StoreStream.getChannels(),
    guildScheduledEventsStore: StoreGuildScheduledEvents = StoreStream.getGuildScheduledEvents(),
    permissionsStore: StorePermissions = StoreStream.getPermissions(),
    voiceParticipants: StoreVoiceParticipants = StoreStream.getVoiceParticipants(),
    storeStateObservable: Observable<StoreState> = observeStores(
        channelId,
        channelsStore,
        guildScheduledEventsStore,
        permissionsStore,
        voiceParticipants,
    ),
) : AppViewModel<ModeratorStartStageViewModel.ViewState>() {

    data class StoreState(
        val channelEvents: List<GuildScheduledEvent>,
        val voiceParticipants: List<UserGuildMember>,
        val canStartEvent: Boolean,
    )

    data class ViewState(
        val channelEvents: List<GuildScheduledEvent>,
        val items: List<ModeratorStartStageItem>,
        /** An event is already running here, so this screen should hand over to the stage call. */
        val channelEventActive: Boolean,
    )

    init {
        storeStateObservable
            .computationLatest()
            .ui(this)
            .appSubscribe(ModeratorStartStageViewModel::class.java) { handleStoreState(it) }
    }

    /** Leaves the stage entirely rather than starting anything. */
    fun disconnectFromStage() {
        selectedVoiceChannelStore.clear()
    }

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        val items = mutableListOf<ModeratorStartStageItem>(ModeratorStartStageItem.Header())

        // At most one event is offered — the soonest one that is actually startable.
        if (storeState.canStartEvent) {
            storeState.channelEvents
                .firstOrNull { ScheduledEventUi.timing(it).isStartable() }
                ?.let { items += ModeratorStartStageItem.ListItem.Event(it) }
        }

        items += ModeratorStartStageItem.ListItem.StaticOption.StartStage
        if (storeState.canStartEvent) {
            items += ModeratorStartStageItem.ListItem.StaticOption.ScheduleEvent
        }
        if (storeState.voiceParticipants.isNotEmpty()) {
            items += ModeratorStartStageItem.Waiting(
                users = storeState.voiceParticipants
                    .take(ModeratorStartStageItem.MAX_DISPLAY_WAITING_USERS),
            )
        }

        updateViewState(
            ViewState(
                channelEvents = storeState.channelEvents,
                items = items,
                channelEventActive = storeState.channelEvents.any {
                    it.status == GuildScheduledEventStatus.ACTIVE
                },
            ),
        )
    }

    companion object {

        /** The participant map re-emits on every speaking frame, hence the throttle. */
        private const val PARTICIPANTS_THROTTLE_MS = 250L

        fun observeStores(
            channelId: Long,
            channelsStore: StoreChannels,
            guildScheduledEventsStore: StoreGuildScheduledEvents,
            permissionsStore: StorePermissions,
            voiceParticipants: StoreVoiceParticipants,
        ): Observable<StoreState> = channelsStore.observeChannel(channelId)
            .filterNull()
            .switchMap { channel ->
                Observable.combineLatest(
                    voiceParticipants.get(channel.id)
                        .leadingEdgeThrottle(PARTICIPANTS_THROTTLE_MS, TimeUnit.MILLISECONDS),
                    guildScheduledEventsStore.observeGuildScheduledEvents(channel.guildId),
                    permissionsStore.observePermissionsForChannel(channel.id),
                ) { participants, guildScheduledEvents, permissions ->
                    StoreState(
                        channelEvents = guildScheduledEvents
                            .filter { it.channelId == channelId }
                            .sortedBy { it.scheduledStartTime },
                        // The moderator is never listed as somebody they are waiting for.
                        voiceParticipants = participants.values
                            .filterNot { it.isMe }
                            .map { UserGuildMember(it.user, it.guildMember) },
                        canStartEvent =
                            PermissionUtils.can(Permission.START_STAGE_EVENT, permissions),
                    )
                }
            }
    }
}
