package com.discord.widgets.voice.fullscreen

import androidx.annotation.MainThread
import com.discord.app.AppViewModel
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreVoiceChannelSelected
import com.discord.stores.StoreVoiceStates
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.voice.VoiceChannelJoinability
import com.discord.utilities.voice.VoiceChannelJoinabilityUtils
import rx.Observable
import rx.subjects.PublishSubject

class WidgetGuildCallOnboardingSheetViewModel internal constructor(
    private val channelId: Long,
    storeStateObservable: Observable<StoreState>,
    private val selectVoiceChannel: (Long) -> Unit,
) : AppViewModel<WidgetGuildCallOnboardingSheetViewModel.ViewState>(ViewState.Uninitialized) {
    private val eventSubject = PublishSubject.create<Event>()

    @JvmOverloads
    constructor(
        channelId: Long,
        selectedVoiceChannelStore: StoreVoiceChannelSelected = StoreStream.getVoiceChannelSelected(),
        permissionsStore: StorePermissions = StoreStream.getPermissions(),
        channelsStore: StoreChannels = StoreStream.getChannels(),
        guildsStore: StoreGuilds = StoreStream.getGuilds(),
        voiceStatesStore: StoreVoiceStates = StoreStream.getVoiceStates(),
    ) : this(
        channelId = channelId,
        storeStateObservable = observeStoreState(
            channelId,
            selectedVoiceChannelStore,
            permissionsStore,
            channelsStore,
            guildsStore,
            voiceStatesStore,
        ),
        selectVoiceChannel = { selectedChannelId ->
            selectedVoiceChannelStore.selectVoiceChannel(selectedChannelId)
                .appSubscribe(WidgetGuildCallOnboardingSheetViewModel::class.java) {}
        },
    )

    init {
        storeStateObservable
            .distinctUntilChanged()
            .computationLatest()
            .ui(this)
            .appSubscribe(WidgetGuildCallOnboardingSheetViewModel::class.java) { storeState ->
                updateViewState(ViewState.Loaded(storeState.guildId, storeState.voiceChannelJoinability))
            }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    @MainThread
    fun onConnectPressed() {
        val state = viewState as? ViewState.Loaded ?: return
        when (state.joinability) {
            VoiceChannelJoinability.CAN_JOIN -> {
                selectVoiceChannel(channelId)
                eventSubject.onNext(Event.LaunchGuildCallScreen(channelId, state.guildId))
            }
            VoiceChannelJoinability.GUILD_VIDEO_AT_CAPACITY ->
                eventSubject.onNext(Event.ShowGuildVideoCapacityDialog)
            else -> Unit
        }
    }

    sealed class Event {
        data class LaunchGuildCallScreen(val channelId: Long, val guildId: Long?) : Event()
        object ShowGuildVideoCapacityDialog : Event()
    }

    data class StoreState(
        val guildId: Long?,
        val voiceChannelJoinability: VoiceChannelJoinability,
    )

    sealed class ViewState {
        data class Loaded(
            val guildId: Long?,
            val joinability: VoiceChannelJoinability,
        ) : ViewState()

        object Uninitialized : ViewState()
    }

    private companion object {
        fun observeStoreState(
            channelId: Long,
            selectedVoiceChannelStore: StoreVoiceChannelSelected,
            permissionsStore: StorePermissions,
            channelsStore: StoreChannels,
            guildsStore: StoreGuilds,
            voiceStatesStore: StoreVoiceStates,
        ): Observable<StoreState> = Observable.combineLatest(
            channelsStore.observeChannel(channelId),
            VoiceChannelJoinabilityUtils.observeJoinability(
                channelId = channelId,
                channelsStore = channelsStore,
                guildsStore = guildsStore,
                permissionsStore = permissionsStore,
                voiceStatesStore = voiceStatesStore,
                voiceChannelSelectedStore = selectedVoiceChannelStore,
            ),
        ) { channel, joinability -> StoreState(channel?.guildId, joinability) }
    }
}
