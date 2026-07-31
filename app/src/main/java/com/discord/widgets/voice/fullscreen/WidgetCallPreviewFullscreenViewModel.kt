package com.discord.widgets.voice.fullscreen

import androidx.annotation.MainThread
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isVoiceChannel
import com.discord.api.user.NsfwAllowance
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelApplicationStream
import com.discord.stores.SelectedChannelAnalyticsLocation
import com.discord.stores.StoreApplication
import com.discord.stores.StoreApplicationStreamPreviews
import com.discord.stores.StoreChannels
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreMediaSettings
import com.discord.stores.StoreMentions
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.StoreUserSettings
import com.discord.stores.StoreVoiceChannelSelected
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.voice.VoiceChannelJoinability
import com.discord.widgets.chat.list.TextInVoiceFeatureFlag
import com.discord.widgets.voice.model.CallModel
import com.discord.widgets.voice.sheet.CallParticipantsAdapter
import rx.Observable
import rx.subjects.PublishSubject

class WidgetCallPreviewFullscreenViewModel internal constructor(
    private val voiceChannelId: Long,
    storeStateObservable: Observable<StoreState>,
    private val isMobileOverlayEnabled: () -> Boolean,
    private val selectVoiceChannel: (Long) -> Unit,
    private val setSelfMuted: (Boolean) -> Unit,
    private val fetchApplication: (Long) -> Unit,
    private val refreshStreams: (
        List<CallParticipantsAdapter.ListItem>,
        MutableSet<String>,
    ) -> Boolean,
    private val selectTextChannel: (Channel?) -> Unit,
    private val closeNavigation: () -> Unit,
    private val selectPreviousChannel: (Long) -> Unit,
) : AppViewModel<WidgetCallPreviewFullscreenViewModel.ViewState>() {

    private val eventSubject = PublishSubject.create<Event>()
    private val fetchedPreviews = linkedSetOf<String>()
    private var mostRecentStoreState: StoreState? = null

    @JvmOverloads
    constructor(
        voiceChannelId: Long,
        storeChannels: StoreChannels = StoreStream.getChannels(),
        storeMentions: StoreMentions = StoreStream.getMentions(),
        storeNavigation: StoreNavigation = StoreStream.getNavigation(),
        storeUserSettings: StoreUserSettings = StoreStream.getUserSettings(),
        storeVoiceChannelSelected: StoreVoiceChannelSelected = StoreStream.getVoiceChannelSelected(),
        storeChannelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
        storeApplicationStreamPreviews: StoreApplicationStreamPreviews =
            StoreStream.getApplicationStreamPreviews(),
        storeApplication: StoreApplication = StoreStream.getApplication(),
        storeUser: StoreUser = StoreStream.getUsers(),
        storeStateObservable: Observable<StoreState> = observeStoreState(
            voiceChannelId,
            storeUser,
            storeChannels,
            storeChannelsSelected,
            storeMentions,
            storeVoiceChannelSelected,
        ),
        mediaSettingsStore: StoreMediaSettings = StoreStream.getMediaSettings(),
    ) : this(
        voiceChannelId = voiceChannelId,
        storeStateObservable = storeStateObservable,
        isMobileOverlayEnabled = storeUserSettings::getIsMobileOverlayEnabled,
        selectVoiceChannel = { storeVoiceChannelSelected.selectVoiceChannel(it) },
        setSelfMuted = { mediaSettingsStore.setSelfMuted(it) },
        fetchApplication = storeApplication::fetchIfNonexisting,
        refreshStreams = { items, fetched ->
            ParticipantsListItemGenerator.refreshStreams(
                items,
                fetched,
                storeApplicationStreamPreviews,
            )
        },
        selectTextChannel = {
            ChannelSelector.getInstance().selectChannel(
                it,
                analyticsLocation = SelectedChannelAnalyticsLocation.TEXT_IN_VOICE,
            )
        },
        closeNavigation = {
            storeNavigation.setNavigationPanelAction(StoreNavigation.PanelAction.CLOSE)
        },
        selectPreviousChannel = { ChannelSelector.getInstance().selectPreviousChannel(it) },
    )

    init {
        storeStateObservable
            .computationLatest()
            .ui(this)
            .distinctUntilChanged()
            .appSubscribe(WidgetCallPreviewFullscreenViewModel::class.java, onNext = ::handleStoreState)
    }

    fun observeEvents(): Observable<Event> = eventSubject

    @MainThread
    fun onStreamPreviewClicked(streamKey: String) {
        val state = mostRecentStoreState as? StoreState.Valid ?: return
        if (state.callModel.voiceChannelJoinability == VoiceChannelJoinability.GUILD_VIDEO_AT_CAPACITY) {
            eventSubject.onNext(Event.ShowGuildVideoAtCapacityDialog)
            return
        }
        eventSubject.onNext(Event.LaunchVideoCall(ModelApplicationStream.decodeStreamKey(streamKey).channelId, streamKey))
    }

    @MainThread
    fun onTextInVoiceTapped() {
        selectTextChannel(viewState?.voiceChannel)
        closeNavigation()
    }

    fun selectTextChannelAfterFinish() {
        val state = viewState ?: return
        if (state.isTextInVoiceChannelSelected) selectPreviousChannel(state.voiceChannel.guildId)
    }

    @JvmOverloads
    @MainThread
    fun tryConnectToVoice(mute: Boolean = false) {
        val state = mostRecentStoreState as? StoreState.Valid ?: return
        if (state.callModel.voiceChannelJoinability == VoiceChannelJoinability.GUILD_VIDEO_AT_CAPACITY) {
            eventSubject.onNext(Event.ShowGuildVideoAtCapacityDialog)
            return
        }
        if (!isMobileOverlayEnabled()) eventSubject.onNext(Event.ShowOverlayNux)
        if (mute) setSelfMuted(true)
        joinVoiceChannel(voiceChannelId)
    }

    private fun handleStoreState(storeState: StoreState) {
        val valid = storeState as? StoreState.Valid ?: return
        mostRecentStoreState = valid

        val callModel = valid.callModel
        val items = ParticipantsListItemGenerator.createConnectedListItems(
            voiceParticipants = callModel.participants,
            mySpectatingStreamKey = callModel.activeStream?.stream?.encodedStreamKey,
            channel = valid.voiceChannel,
            callModel = callModel,
            embeddedActivities = callModel.embeddedActivitiesForChannel.values.toList(),
            applications = callModel.applications,
        )

        if (valid.selectedVoiceChannel?.id == voiceChannelId) {
            eventSubject.onNext(Event.LaunchVideoCall(voiceChannelId, null))
            return
        }

        callModel.embeddedActivitiesForChannel.keys.forEach(fetchApplication)
        val participantsList = if (refreshStreams(items, fetchedPreviews)) {
            ParticipantsList.ListItems(items)
        } else {
            ParticipantsList.Empty(items)
        }
        updateViewState(
            ViewState(
                voiceChannel = valid.voiceChannel,
                isTextInVoiceChannelSelected = valid.selectedTextChannel.isVoiceChannel(),
                textInVoiceEnabled = valid.isTextInVoiceEnabled,
                titleText = valid.voiceChannel.getDisplayName(),
                otherChannelsMentionsCount =
                    (valid.totalMentionsCount - valid.channelMentionsCount).coerceAtLeast(0),
                channelMentionsCount = valid.channelMentionsCount,
                isConnectEnabled = callModel.voiceChannelJoinability !=
                    VoiceChannelJoinability.PERMISSIONS_MISSING,
                participantsList = participantsList,
                isNsfwUnconsented = valid.isNsfwUnconsented,
                isChannelNsfw = valid.isChannelNsfw,
                nsfwAllowed = valid.nsfwAllowed,
            ),
        )
    }

    @MainThread
    private fun joinVoiceChannel(channelId: Long) {
        selectVoiceChannel(channelId)
        eventSubject.onNext(Event.LaunchVideoCall(voiceChannelId, null))
    }

    sealed class Event {
        data class LaunchVideoCall(val channelId: Long, val autoTargetStreamKey: String?) : Event()
        object ShowGuildVideoAtCapacityDialog : Event()
        object ShowOverlayNux : Event()
    }

    sealed class ParticipantsList {
        data class Empty(val items: List<CallParticipantsAdapter.ListItem>) : ParticipantsList()
        data class ListItems(val items: List<CallParticipantsAdapter.ListItem>) : ParticipantsList()
    }

    sealed class StoreState {
        object Invalid : StoreState()

        data class Valid(
            val voiceChannel: Channel,
            val selectedTextChannel: Channel,
            val isTextInVoiceEnabled: Boolean,
            val totalMentionsCount: Int,
            val channelMentionsCount: Int,
            val isNsfwUnconsented: Boolean,
            val isChannelNsfw: Boolean,
            val nsfwAllowed: NsfwAllowance?,
            val callModel: CallModel,
            val selectedVoiceChannel: Channel?,
        ) : StoreState()
    }

    data class ViewState(
        val voiceChannel: Channel,
        val isTextInVoiceChannelSelected: Boolean,
        val textInVoiceEnabled: Boolean,
        val titleText: String?,
        val otherChannelsMentionsCount: Int,
        val channelMentionsCount: Int,
        val isConnectEnabled: Boolean,
        val participantsList: ParticipantsList,
        val isNsfwUnconsented: Boolean,
        val isChannelNsfw: Boolean,
        val nsfwAllowed: NsfwAllowance?,
    )

    private companion object {
        fun observeStoreState(
            voiceChannelId: Long,
            storeUser: StoreUser,
            storeChannels: StoreChannels,
            storeChannelsSelected: StoreChannelsSelected,
            storeMentions: StoreMentions,
            storeVoiceChannelSelected: StoreVoiceChannelSelected,
        ): Observable<StoreState> = storeChannels.observeChannel(voiceChannelId).switchMap { voiceChannel ->
            Observable.combineLatest(
                storeUser.observeMe(),
                storeChannelsSelected.observeSelectedChannel(),
                TextInVoiceFeatureFlag.observeEnabled(voiceChannel?.guildId),
                storeMentions.observeTotalMentions(),
                storeMentions.observeMentionCounts(),
                CallModel.get(voiceChannelId),
                storeVoiceChannelSelected.observeSelectedChannel(),
            ) { me,
                selectedTextChannel,
                isTextInVoiceEnabled,
                totalMentionsCount,
                mentionCounts,
                callModel,
                selectedVoiceChannel ->
                if (voiceChannel == null || selectedTextChannel == null || callModel == null) {
                    StoreState.Invalid
                } else {
                    StoreState.Valid(
                        voiceChannel = voiceChannel,
                        selectedTextChannel = selectedTextChannel,
                        isTextInVoiceEnabled = isTextInVoiceEnabled,
                        totalMentionsCount = totalMentionsCount,
                        channelMentionsCount = mentionCounts[voiceChannel.id] ?: 0,
                        isNsfwUnconsented =
                            !StoreStream.getGuildsNsfw().isGuildNsfwGateAgreed(voiceChannel.guildId),
                        isChannelNsfw = voiceChannel.nsfw,
                        nsfwAllowed = me.nsfwAllowance,
                        callModel = callModel,
                        selectedVoiceChannel = selectedVoiceChannel,
                    )
                }
            }
        }
    }
}
