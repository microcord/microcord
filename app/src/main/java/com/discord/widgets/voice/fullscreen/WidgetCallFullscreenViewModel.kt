package com.discord.widgets.voice.fullscreen

import android.content.Intent
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import co.discord.media_engine.VideoInputDeviceDescription
import co.discord.media_engine.VideoInputDeviceFacing
import com.discord.R
import com.discord.api.application.ApplicationAsset
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.channel.isVoiceChannel
import com.discord.api.guild.GuildMaxVideoChannelUsers
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.api.permission.Permission
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelApplicationStream
import com.discord.restapi.RestAPIParams
import com.discord.rtcconnection.RtcConnection
import com.discord.stores.SelectedChannelAnalyticsLocation
import com.discord.stores.StoreAccessibility
import com.discord.stores.StoreAnalytics
import com.discord.stores.StoreApplication
import com.discord.stores.StoreApplicationAssets
import com.discord.stores.StoreApplicationStreaming
import com.discord.stores.StoreAudioManagerV2
import com.discord.stores.StoreChannels
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreConnectivity
import com.discord.stores.StoreExperiments
import com.discord.stores.StoreGuildSelected
import com.discord.stores.StoreMediaEngine
import com.discord.stores.StoreMediaSettings
import com.discord.stores.StoreMentions
import com.discord.stores.StoreNavigation
import com.discord.stores.StorePermissions
import com.discord.rtcconnection.socket.io.Payloads.ResolutionType
import com.discord.stores.StoreStageChannels
import com.discord.stores.StoreStream
import com.discord.stores.StoreStreamRtcConnection
import com.discord.stores.StoreTabsNavigation
import com.discord.stores.StoreUserSettings
import com.discord.stores.StoreVoiceChannelSelected
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.permissions.VideoPermissionsManager
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.user.UserUtils
import com.discord.utilities.video.VideoPlayerIdleDetector
import com.discord.utilities.voice.VoiceEngineServiceController
import com.discord.views.calls.VideoCallParticipantView
import com.discord.widgets.guildscheduledevent.ScheduledEventUi
import com.discord.widgets.stage.StageRoles
import com.discord.widgets.stage.model.StageCallModel
import com.discord.widgets.voice.controls.VoiceControlsOutputSelectorState
import com.discord.widgets.voice.model.CallModel
import com.discord.widgets.voice.model.CameraState
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.pow
import org.webrtc.RendererCommon
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject

class WidgetCallFullscreenViewModel constructor(
    channelId: Long,
    private val storeChannels: StoreChannels = StoreStream.getChannels(),
    private val storeNavigation: StoreNavigation = StoreStream.getNavigation(),
    private val selectedVoiceChannelStore: StoreVoiceChannelSelected = StoreStream.getVoiceChannelSelected(),
    private val guildSelectedStore: StoreGuildSelected = StoreStream.getGuildSelected(),
    private val tabsNavigationStore: StoreTabsNavigation = StoreStream.getTabsNavigation(),
    private val userSettingsStore: StoreUserSettings = StoreStream.getUserSettings(),
    private val applicationStreamingStore: StoreApplicationStreaming = StoreStream.getApplicationStreaming(),
    private val mediaEngineStore: StoreMediaEngine = StoreStream.getMediaEngine(),
    private val mediaSettingsStore: StoreMediaSettings = StoreStream.getMediaSettings(),
    private val permissionsStore: StorePermissions = StoreStream.getPermissions(),
    private val clock: Clock = ClockFactory.get(),
    private val backgroundThreadScheduler: Scheduler = Schedulers.computation(),
    private val videoPermissionsManager: VideoPermissionsManager = VideoPermissionsManager(),
    private val voiceEngineServiceController: VoiceEngineServiceController = VoiceEngineServiceController.INSTANCE,
    private val streamRtcConnectionStore: StoreStreamRtcConnection = StoreStream.getStreamRtcConnection(),
    private val accessibilityStore: StoreAccessibility = StoreStream.getAccessibility(),
    private val audioManagerStore: StoreAudioManagerV2 = StoreStream.getAudioManagerV2(),
    private val mentionsStore: StoreMentions = StoreStream.getMentions(),
    private val analyticsStore: StoreAnalytics = StoreStream.getAnalytics(),
    private val connectivityStore: StoreConnectivity = StoreStream.getConnectivity(),
    private val stageStore: StoreStageChannels = StoreStream.getStageChannels(),
    private val storeAnalytics: StoreAnalytics = StoreStream.getAnalytics(),
    private val experimentsStore: StoreExperiments = StoreStream.getExperiments(),
    private val channelsSelectedStore: StoreChannelsSelected = StoreStream.getChannelsSelected(),
    private val applicationStore: StoreApplication = StoreStream.getApplication(),
    private val applicationAssetsStore: StoreApplicationAssets = StoreStream.getApplicationAssets(),
    streamKey: String? = null,
) : AppViewModel<WidgetCallFullscreenViewModel.ViewState>(ViewState.Uninitialized) {

    private var channelId = channelId
    private var allVideoParticipants: List<CallParticipant> = emptyList()
    private var autotargetStreamKey: String? = streamKey

    /**
     * A stream just asked for whose request the streaming store has not echoed yet. Its tile shows
     * as connecting meanwhile, instead of flashing "Watch stream" for the hop it takes to arrive.
     */
    private var pendingStreamKey: String? = streamKey
    private var bottomSheetState: Int? = null
    private var focusedVideoParticipantKey: String? = null
    private var forwardVideoGridInteractionSubscription: Subscription? = null
    private var lastParticipantTap: ParticipantTap? = null
    private var logStreamQualityIndicatorViewed = true
    private var mostRecentStoreState: StoreState? = null
    private var startedAsVideo: Boolean? = null
    private var storeObservableSubscription: Subscription? = null
    private var wasEverConnected = false
    private var wasEverMultiParticipant = false

    var showStageCallBottomSheet = channelId != selectedVoiceChannelStore.getSelectedVoiceChannelId()

    private val eventSubject = PublishSubject.create<Event>()
    private val offScreenSubject = PublishSubject.create<List<VideoCallParticipantView.ParticipantData>>()
    private val videoPlayerIdleDetectorFooter = VideoPlayerIdleDetector { onIdleStateChanged(isIdleFooter = it) }
    private val videoPlayerIdleDetectorHeader = VideoPlayerIdleDetector { onIdleStateChanged(isIdleHeader = it) }

    init {
        if (streamKey != null) {
            selectedVoiceChannelStore.selectVoiceChannel(ModelApplicationStream.decodeStreamKey(streamKey).channelId)
        }
        offScreenSubject
            .debounce(1, TimeUnit.SECONDS)
            .ui(this)
            .appSubscribe(WidgetCallFullscreenViewModel::class.java, onNext = ::setOffScreenVideoParticipants)
    }

    fun observeEvents(): Observable<Event> = eventSubject

    @MainThread
    fun handleStoreState(storeState: StoreState) {
        if (storeState === StoreState.Invalid) {
            updateViewState(ViewState.Invalid)
            mostRecentStoreState = storeState
            return
        }
        val state = storeState as StoreState.Valid
        val callModel = state.callModel
        val isStage = callModel.channel.isStageVoiceChannel()

        if (startedAsVideo == null) startedAsVideo = callModel.isVideoCall
        val previous = mostRecentStoreState as? StoreState.Valid
        if (previous?.callModel?.isChannelSelected == true && !callModel.isChannelSelected) {
            updateViewState(ViewState.Invalid)
            mostRecentStoreState = storeState
            return
        }

        val previousViewState = viewState as? ViewState.Valid
        var isIdleHeader = previousViewState?.isIdleHeader ?: false
        val channelMentionsBefore = previous?.channelMentionsCount ?: 0
        val unreadsBefore = previous?.unreadsCount ?: 0
        if (channelMentionsBefore < state.channelMentionsCount || unreadsBefore < state.unreadsCount) {
            videoPlayerIdleDetectorHeader.onInteraction()
            isIdleHeader = false
        }

        val activeStreamKey = callModel.activeStream?.stream?.encodedStreamKey
        val embeddedParticipants = if (callModel.isConnected && !isStage) {
            callModel.embeddedActivitiesForChannel.mapNotNull { (applicationId, activity) ->
                applicationStore.fetchIfNonexisting(applicationId)
                applicationAssetsStore.fetchIfNonExisting(applicationId)
                callModel.applications[applicationId]?.let { application ->
                    CallParticipant.EmbeddedActivityParticipant(
                        embeddedActivity = activity,
                        participantsInActivity = activity.userIds.mapNotNull { userId ->
                            callModel.participants[userId]?.let {
                                com.discord.models.guild.UserGuildMember(it.user, it.guildMember)
                            }
                        },
                        application = com.discord.models.commands.Application.fromApiApplication(application),
                        backgroundAsset = state.embeddedAppBackgrounds[applicationId],
                    )
                }
            }
        } else {
            emptyList()
        }

        if (callModel.activeStream?.stream?.encodedStreamKey == pendingStreamKey) pendingStreamKey = null
        val users = when {
            !callModel.isConnected -> emptyList()
            isStage -> state.stageCallModel?.mediaParticipants?.map { it.voiceUser }.orEmpty()
            else -> callModel.participants.values.sortedWith(createUserItemsComparator(activeStreamKey))
        }
        val userParticipants = users.flatMap {
            createVideoGridEntriesForParticipant(
                participant = it,
                channelId = callModel.channel.id,
                activeApplicationStream = callModel.activeStream,
                streamQuality = state.streamQuality,
                selectedVideoInputDevice = callModel.selectedVideoDevice,
                showLabels = !(previousViewState?.isIdleFooter ?: false),
            )
        }
            .filter { !callModel.voiceSettings.voiceParticipantsHidden || it.hasActiveVideo() }
            .filter { participant ->
                !callModel.voiceSettings.selfVideoHidden ||
                    (participant as? CallParticipant.UserOrStreamParticipant)
                        ?.participantData?.voiceParticipant?.user?.id != callModel.myId
            }

        allVideoParticipants = embeddedParticipants + userParticipants
        if (isOneOnOneMeCall(allVideoParticipants)) focusedVideoParticipantKey = null
        val visibleParticipants = computeVisibleVideoParticipants()
        val cameraState = when {
            isStage || !callModel.isConnected -> CameraState.CAMERA_DISABLED
            callModel.selectedVideoDevice != null -> CameraState.CAMERA_ON
            else -> CameraState.CAMERA_OFF
        }
        val anyoneUsingVideo = callModel.participants.values.any {
            it.isConnected && (it.voiceState?.selfVideo == true || it.voiceState?.selfStream == true)
        }
        val displayMode = when {
            callModel.channel.isPrivate() && !anyoneUsingVideo -> DisplayMode.PRIVATE_CALL_PARTICIPANTS
            isStage -> DisplayMode.STAGE
            else -> DisplayMode.GRID
        }
        val outputSelectorState = outputSelectorState(callModel)

        autotargetStreamKey?.let { key ->
            if (allVideoParticipants.any {
                    (it as? CallParticipant.UserOrStreamParticipant)
                        ?.participantData?.voiceParticipant?.applicationStream?.encodedStreamKey == key
                }
            ) {
                targetAndFocusStream(key)
            }
            autotargetStreamKey = null
        }

        val showLowConnectivity = state.connectivityState.state in setOf(
            StoreConnectivity.State.OFFLINE,
            StoreConnectivity.State.OFFLINE_AIRPLANE_MODE,
        ) && displayMode == DisplayMode.PRIVATE_CALL_PARTICIPANTS

        if (isStage && previous?.myStageRoles?.isSpeaker == false && state.myStageRoles?.isSpeaker == true &&
            !PermissionUtils.can(Permission.USE_VAD, state.myPermissions) &&
            callModel.inputMode != com.discord.rtcconnection.mediaengine.MediaEngineConnection.InputMode.PUSH_TO_TALK
        ) {
            eventSubject.onNext(Event.ShowNoVadPermissionDialog)
        }

        wasEverMultiParticipant = wasEverMultiParticipant || callModel.numUsersConnected > 1
        wasEverConnected = wasEverConnected || callModel.isConnected
        val allUsers = allVideoParticipants.filterIsInstance<CallParticipant.UserOrStreamParticipant>()
            .associateBy {
                val prefix = if (it.participantData.type ==
                    VideoCallParticipantView.ParticipantData.Type.APPLICATION_STREAMING
                ) "STREAM" else "USER"
                "$prefix-${it.participantData.voiceParticipant.user.id}"
            }

        val startableEvent = callModel.guildScheduledEvents
            .sortedBy { it.scheduledStartTime }
            .firstOrNull { ScheduledEventUi.timing(it).isStartable() }
        val activeEvent = callModel.guildScheduledEvents.firstOrNull {
            it.status == GuildScheduledEventStatus.ACTIVE
        }

        updateViewState(
            ViewState.Valid(
                callModel = callModel,
                stageCallModel = state.stageCallModel,
                overlayStatus = if (state.overlayEnabled) {
                    OverlayStatus.ENABLED
                } else OverlayStatus.DISABLED,
                visibleVideoParticipants = visibleParticipants,
                isIdleFooter = previousViewState?.isIdleFooter ?: false,
                isIdleHeader = isIdleHeader,
                isControlFadingDisabled = previousViewState?.isControlFadingDisabled ?: false,
                isSwitchCameraButtonVisible = callModel.videoDevices.size >= 2 && cameraState == CameraState.CAMERA_ON,
                cameraState = cameraState,
                displayMode = displayMode,
                outputSelectorState = outputSelectorState,
                focusedParticipantKey = focusedVideoParticipantKey,
                isAnyoneUsingVideo = anyoneUsingVideo,
                pipParticipant = computePipParticipant(
                    callModel.myId,
                    callModel.selectedVideoDevice,
                    callModel.isStreaming,
                    cameraState,
                ),
                noiseCancellation = state.noiseCancellation,
                privateCallUserListItems = callModel.participants.values.toList(),
                showParticipantsHiddenView = callModel.voiceSettings.voiceParticipantsHidden &&
                    !callModel.isStreaming && visibleParticipants.isEmpty() && displayMode == DisplayMode.GRID,
                lockCallScreenControls = state.isScreenReaderEnabled,
                startedAsVideo = startedAsVideo ?: false,
                perceptualStreamVolume = amplitudeToPerceptual(state.streamVolume, MAX_STREAM_VOLUME),
                totalMentionsCount = (state.totalMentionsCount - state.channelMentionsCount).coerceAtLeast(0),
                showLowConnectivityBar = showLowConnectivity,
                stageRoles = state.myStageRoles,
                isUpdatingRequestToSpeak = previousViewState?.isUpdatingRequestToSpeak ?: false,
                isMovingToAudience = previousViewState?.isMovingToAudience ?: false,
                requestingToSpeakCount = state.stageCallModel?.requestingToSpeakCount ?: 0,
                stopOffscreenVideo = state.stopOffscreenVideo,
                channelPermissions = state.myPermissions,
                startableEvent = startableEvent,
                activeEvent = activeEvent,
                textInVoiceMentionsCount = state.channelMentionsCount,
                textInVoiceUnreadsCount = state.unreadsCount,
                isTextInVoiceChannelSelected = state.isTextInVoiceChannelSelected,
                allVideoParticipants = allUsers,
            ),
        )
        mostRecentStoreState = storeState
    }

    override fun modifyPendingViewState(currentViewState: ViewState?, pendingViewState: ViewState): ViewState {
        if (currentViewState !is ViewState.Valid || pendingViewState !is ViewState.Valid) return pendingViewState
        val showLabel = !pendingViewState.isIdleFooter && pendingViewState.visibleVideoParticipants.size != 1
        val fullScreen = focusedVideoParticipantKey != null
        return pendingViewState.copy(
            visibleVideoParticipants = pendingViewState.visibleVideoParticipants.map { participant ->
                if (participant !is CallParticipant.UserOrStreamParticipant) participant
                else if (participant.participantData.showLabel == showLabel &&
                    participant.participantData.isFullScreen == fullScreen
                ) participant
                else participant.copy(
                    participantData = participant.participantData.copy(
                        showLabel = showLabel,
                        isFullScreen = fullScreen,
                    ),
                )
            },
        )
    }

    @MainThread
    override fun updateViewState(viewState: ViewState) {
        val previous = this.viewState as? ViewState.Valid
        super.updateViewState(viewState)
        if (viewState !is ViewState.Valid) {
            clearFocusedParticipants()
            return
        }

        val analyticsVideoLayout = viewState.analyticsVideoLayout
        if (analyticsVideoLayout != null && analyticsVideoLayout != previous?.analyticsVideoLayout) {
            analyticsStore.trackVideoLayoutToggled(
                analyticsVideoLayout,
                viewState.callModel.myId,
                viewState.callModel.channel,
            )
        }
        if (viewState.displayMode != previous?.displayMode) {
            when (viewState.displayMode) {
                DisplayMode.GRID -> startIdle()
                DisplayMode.PRIVATE_CALL_PARTICIPANTS -> stopIdle()
                DisplayMode.STAGE -> Unit
            }
        }
        negotiateFocusedParticipant(viewState)
    }

    @MainThread
    fun disableControlFading() {
        val state = viewState as? ViewState.Valid ?: return
        if (!state.isControlFadingDisabled) updateViewState(state.copy(isControlFadingDisabled = true))
    }

    @MainThread
    fun disconnect() {
        videoPlayerIdleDetectorFooter.onPreventIdle()
        videoPlayerIdleDetectorHeader.onPreventIdle()
        selectedVoiceChannelStore.clear()
        if (!wasEverConnected || wasEverMultiParticipant) {
            val state = viewState as? ViewState.Valid ?: return
            eventSubject.onNext(
                Event.EnqueueCallFeedbackSheet(
                    channelId,
                    state.callModel.rtcConnectionMetadata?.rtcConnectionId,
                    state.callModel.rtcConnectionMetadata?.mediaSessionId,
                    state.callModel.getCallDurationMs(clock),
                ),
            )
        }
    }

    @MainThread
    fun focusVideoParticipant(participantKey: String) {
        val state = viewState as? ViewState.Valid ?: return
        if (!isOneOnOneMeCall(allVideoParticipants)) focusedVideoParticipantKey = participantKey
        updateViewState(
            state.copy(
                visibleVideoParticipants = computeVisibleVideoParticipants(),
                focusedParticipantKey = focusedVideoParticipantKey,
                pipParticipant = computePipParticipant(
                    state.callModel.myId,
                    state.callModel.selectedVideoDevice,
                    state.callModel.isStreaming,
                    state.cameraState,
                ),
            ),
        )
    }

    @MainThread
    fun handleBottomSheetState(bottomSheetState: Int) {
        this.bottomSheetState = bottomSheetState
    }

    @MainThread
    fun moveMeToAudience() {
        val state = viewState as? ViewState.Valid ?: return
        if (state.isMovingToAudience) return
        updateViewState(state.copy(isMovingToAudience = true))
        updateMyVoiceState(state.callModel.channel, suppress = true, requestToSpeakTimestamp = null) {
            (viewState as? ViewState.Valid)?.let { updateViewState(it.copy(isMovingToAudience = false)) }
        }
    }

    @MainThread
    fun onCameraPermissionsGranted() {
        mediaEngineStore.selectDefaultVideoDevice()
        eventSubject.onNext(Event.AccessibilityAnnouncement(R.string.camera_a11y_turned_on))
    }

    @MainThread
    fun onDeafenPressed() {
        preventIdle()
        val state = mostRecentStoreState as? StoreState.Valid
        if (state?.callModel?.isServerDeafened == true) {
            eventSubject.onNext(Event.ShowServerDeafenedDialog)
            return
        }
        mediaSettingsStore.toggleSelfDeafened()
        (viewState as? ViewState.Valid)?.let {
            eventSubject.onNext(
                Event.AccessibilityAnnouncement(
                    if (it.isDeafened) R.string.voice_channel_undeafened else R.string.voice_channel_deafened,
                ),
            )
        }
    }

    @MainThread
    fun onEmptyStateTapped() = startTapForwardingJob()

    @MainThread
    fun onGridParticipantLongPressed(participant: CallParticipant) {
        val data = (participant as? CallParticipant.UserOrStreamParticipant)?.participantData ?: return
        if (data.type == VideoCallParticipantView.ParticipantData.Type.DEFAULT) {
            eventSubject.onNext(Event.ShowUserSheet(data.voiceParticipant.user.id, channelId))
        }
    }

    @MainThread
    fun onGridParticipantTapped(callParticipant: CallParticipant) {
        if (callParticipant is CallParticipant.EmbeddedActivityParticipant) {
            eventSubject.onNext(Event.ShowActivitiesDesktopOnlyDialog)
        }
        val key = callParticipant.participantFocusKey()
        val tap = ParticipantTap(key, clock.currentTimeMillis())
        val previous = lastParticipantTap
        lastParticipantTap = tap
        if (previous?.participantFocusKey == key && tap.timestamp - previous.timestamp <= DOUBLE_TAP_THRESHOLD_MS &&
            callParticipant is CallParticipant.UserOrStreamParticipant
        ) {
            if (focusedVideoParticipantKey == key) clearFocusedVideoParticipant() else focusVideoParticipant(key)
            cancelTapForwardingJob()
            lastParticipantTap = null
        } else {
            startTapForwardingJob()
        }
    }

    @MainThread
    fun onMuteClicked() {
        preventIdle()
        val state = viewState as? ViewState.Valid
        when {
            state?.callModel?.isSuppressed == true -> eventSubject.onNext(Event.ShowSuppressedDialog)
            state?.callModel?.isMuted == true -> eventSubject.onNext(Event.ShowServerMutedDialog)
            mediaSettingsStore.toggleSelfMuted() == StoreMediaSettings.SelfMuteFailure.CANNOT_USE_VAD ->
                eventSubject.onNext(Event.ShowNoVadPermissionDialog)
            state != null -> eventSubject.onNext(
                Event.AccessibilityAnnouncement(
                    if (state.callModel.isMeMutedByAnySource) R.string.voice_channel_unmuted
                    else R.string.voice_channel_muted,
                ),
            )
        }
    }

    @MainThread
    fun onPushToTalkPressed(isPressed: Boolean) {
        if (isPressed) stopIdle() else startIdle()
        mediaEngineStore.setPttActive(isPressed)
    }

    @MainThread
    fun onScreenShareClick() {
        val state = viewState as? ViewState.Valid ?: return
        if (state.callModel.isStreaming) {
            stopScreenShare()
        } else if (!hasVideoPermission()) {
            eventSubject.onNext(Event.ShowNoScreenSharePermissionDialog)
        } else {
            eventSubject.onNext(Event.RequestStartStream)
        }
    }

    @MainThread
    fun onShowParticipantsPressed() = mediaSettingsStore.updateVoiceParticipantsHidden(false)

    fun onStreamPerceptualVolumeChanged(perceptualVolume: Float, fromUser: Boolean) {
        if (!fromUser) return
        streamRtcConnectionStore.updateStreamVolume(perceptualToAmplitude(perceptualVolume, MAX_STREAM_VOLUME))
        audioManagerStore.updateMediaVolume(perceptualVolume / MAX_STREAM_VOLUME)
    }

    @MainThread
    fun onStreamQualityIndicatorClicked(
        resolution: VideoCallParticipantView.StreamResolution,
        fps: VideoCallParticipantView.StreamFps?,
    ) {
        if (resolution.isHighDefinition || fps?.isHighFrameRate == true) {
            eventSubject.onNext(Event.NavigateToPremiumSettings)
        }
    }

    @MainThread
    fun onStreamQualityIndicatorShown(
        resolution: VideoCallParticipantView.StreamResolution,
        fps: VideoCallParticipantView.StreamFps?,
    ) {
        val highResolution = resolution.isHighDefinition
        val highFps = fps?.isHighFrameRate
        if (logStreamQualityIndicatorViewed && (highResolution || highFps == true)) {
            logStreamQualityIndicatorViewed = false
            storeAnalytics.streamQualityIndicatorViewed(highResolution, highFps)
        }
    }

    @MainThread
    fun onTextInVoiceTapped() {
        val state = viewState as? ViewState.Valid ?: return
        ChannelSelector.getInstance().selectChannel(
            state.callModel.channel,
            analyticsLocation = SelectedChannelAnalyticsLocation.TEXT_IN_VOICE,
        )
        storeNavigation.setNavigationPanelAction(StoreNavigation.PanelAction.CLOSE)
    }

    fun onVisitCommunityButtonClicked(guildId: Long) {
        guildSelectedStore.set(guildId)
        tabsNavigationStore.selectHomeTab(StoreNavigation.PanelAction.CLOSE)
    }

    fun requestStopWatchingStreamFromUserInput() {
        val state = viewState as? ViewState.Valid
        stopWatchingStream()
        state?.callModel?.activeStream?.stream?.let {
            eventSubject.onNext(
                Event.EnqueueStreamFeedbackSheet(
                    it.encodedStreamKey,
                    state.callModel.rtcConnectionMetadata?.mediaSessionId,
                ),
            )
        }
    }

    fun selectTextChannelAfterFinish() {
        val state = viewState as? ViewState.Valid ?: return
        if (state.isTextInVoiceChannelSelected) {
            state.callModel.guild?.let { ChannelSelector.getInstance().selectPreviousChannel(it.id) }
        }
    }

    fun setTargetChannelId(channelId: Long) {
        storeObservableSubscription?.unsubscribe()
        this.channelId = channelId
        observeStoreState()
            .distinctUntilChanged()
            .computationLatest()
            .ui(this)
            .appSubscribe(
                WidgetCallFullscreenViewModel::class.java,
                subscriptionHandler = { storeObservableSubscription = it },
                onNext = ::handleStoreState,
            )
    }

    @MainThread
    fun startIdle() {
        val state = viewState as? ViewState.Valid ?: return
        if (!state.isControlFadingDisabled && state.displayMode == DisplayMode.GRID && bottomSheetState != 3) {
            videoPlayerIdleDetectorFooter.beginIdleDetection()
            videoPlayerIdleDetectorHeader.beginIdleDetection()
        }
    }

    @MainThread
    fun startScreenShare(intent: Intent) {
        preventIdle()
        voiceEngineServiceController.startStream(intent)
    }

    @MainThread
    fun stopIdle() {
        videoPlayerIdleDetectorFooter.endIdleDetection()
        videoPlayerIdleDetectorHeader.endIdleDetection()
    }

    @MainThread
    fun stopScreenShare() {
        preventIdle()
        voiceEngineServiceController.stopStream()
        val state = viewState as? ViewState.Valid ?: return
        state.callModel.activeStream?.stream?.let {
            eventSubject.onNext(
                Event.EnqueueStreamFeedbackSheet(
                    it.encodedStreamKey,
                    state.callModel.rtcConnectionMetadata?.mediaSessionId,
                ),
            )
        }
    }

    @MainThread
    fun switchCameraInputPressed() {
        preventIdle()
        val state = viewState as? ViewState.Valid ?: return
        if (state.cameraState == CameraState.CAMERA_ON) mediaEngineStore.cycleVideoInputDevice()
    }

    @MainThread
    fun targetAndFocusStream(streamKey: String) {
        pendingStreamKey = streamKey
        targetStream(streamKey)
        focusVideoParticipant("${ModelApplicationStream.decodeStreamKey(streamKey).ownerId}--STREAM")
    }

    @MainThread
    fun targetStream(streamKey: String) = applicationStreamingStore.targetStream(streamKey)

    @MainThread
    fun toggleCameraPressed() {
        preventIdle()
        val state = viewState as? ViewState.Valid ?: return
        if (state.cameraState == CameraState.CAMERA_DISABLED) return
        if (!hasVideoPermission()) {
            eventSubject.onNext(Event.ShowNoVideoPermissionDialog)
            return
        }
        if (state.cameraState == CameraState.CAMERA_ON) {
            mediaEngineStore.selectVideoInputDevice(null)
            eventSubject.onNext(Event.AccessibilityAnnouncement(R.string.camera_a11y_turned_off))
            return
        }
        val capacity = state.callModel.guildMaxVideoChannelMembers
        if (capacity is GuildMaxVideoChannelUsers.Limited && state.callModel.numUsersConnected > capacity.limit) {
            eventSubject.onNext(Event.ShowCameraCapacityDialog(capacity.limit))
        } else {
            eventSubject.onNext(Event.ShowRequestCameraPermissionsDialog)
        }
    }

    @MainThread
    fun toggleRequestToSpeak() {
        val state = viewState as? ViewState.Valid ?: return
        if (state.isUpdatingRequestToSpeak) return
        updateViewState(state.copy(isUpdatingRequestToSpeak = true))
        val raisingHand = !state.callModel.isMyHandRaised
        updateMyVoiceState(
            state.callModel.channel,
            suppress = if (raisingHand) null else true,
            requestToSpeakTimestamp = if (raisingHand) UtcDateTime(clock.currentTimeMillis()) else null,
        ) {
            (viewState as? ViewState.Valid)?.let { updateViewState(it.copy(isUpdatingRequestToSpeak = false)) }
        }
    }

    @MainThread
    fun tryConnectToVoice() = selectedVoiceChannelStore.selectVoiceChannel(channelId)

    fun updateOffScreenParticipantsFromScroll(
        visibleVideoParticipants: List<VideoCallParticipantView.ParticipantData>,
    ) = offScreenSubject.onNext(visibleVideoParticipants)

    override fun onCleared() {
        storeObservableSubscription?.unsubscribe()
        cancelTapForwardingJob()
        videoPlayerIdleDetectorFooter.dispose()
        videoPlayerIdleDetectorHeader.dispose()
        clearFocusedParticipants()
        super.onCleared()
    }

    private fun observeStoreState(): Observable<StoreState> =
        storeChannels.observeChannel(channelId).switchMap { channel ->
            if (channel == null) return@switchMap Observable.just(StoreState.Invalid)
            CallModel.get(channelId).switchMap { callModel ->
                if (callModel == null) return@switchMap Observable.just(StoreState.Invalid)
                val first = Observable.combineLatest(
                    accessibilityStore.observeScreenreaderEnabled(),
                    mentionsStore.observeTotalMentions(),
                    connectivityStore.observeState(),
                    stageStore.observeMyRoles(channelId),
                ) { screenReader, mentions, connectivity, stageRoles ->
                    ObservationFirst(screenReader, mentions, connectivity, stageRoles)
                }
                val second = Observable.combineLatest(
                    experimentsStore.observeUserExperiment(STOP_OFFSCREEN_VIDEO_EXPERIMENT, true),
                    channelsSelectedStore.observeSelectedChannel(),
                    mentionsStore.observeMentionCounts(),
                ) { experiment, selectedChannel, mentionCounts ->
                    ObservationSecond(experiment?.bucket == 1, selectedChannel, mentionCounts)
                }
                val third = Observable.combineLatest(
                    StoreStream.getReadStates().observeUnreadCountForChannel(
                        channelId,
                        UtcDateTime(callModel.timeConnectedMs),
                    ),
                    applicationAssetsStore.observeEmbeddedAppBackgrounds(),
                    userSettingsStore.observeIsMobileOverlayEnabled(),
                ) { unreadCount, backgrounds, overlayEnabled ->
                    ObservationThird(unreadCount, backgrounds, overlayEnabled)
                }

                Observable.combineLatest(
                    StageCallModel.observeStageCallModel(channelId),
                    permissionsStore.observePermissionsForChannel(channelId),
                    streamRtcConnectionStore.observeStreamVolume().distinctUntilChanged(),
                    streamRtcConnectionStore.observeConnectionQuality().distinctUntilChanged(),
                    first,
                    second,
                    third,
                ) { stageCall, permissions, streamVolume, streamQuality, a, b, c ->
                    StoreState.Valid(
                        callModel = callModel,
                        stageCallModel = stageCall,
                        noiseCancellation = callModel.voiceSettings.noiseProcessing ==
                            StoreMediaSettings.NoiseProcessing.Cancellation,
                        myPermissions = permissions,
                        streamVolume = streamVolume,
                        streamQuality = streamQuality,
                        isScreenReaderEnabled = a.screenReader,
                        totalMentionsCount = a.totalMentions,
                        connectivityState = a.connectivity,
                        myStageRoles = a.stageRoles,
                        stopOffscreenVideo = b.stopOffscreenVideo,
                        isTextInVoiceChannelSelected = b.selectedChannel?.isVoiceChannel() == true,
                        channelMentionsCount = b.mentionCounts[channelId] ?: 0,
                        unreadsCount = c.unreadCount,
                        embeddedAppBackgrounds = c.backgrounds,
                        overlayEnabled = c.overlayEnabled,
                    )
                }
            }
        }

    private fun createUserItemsComparator(
        @Suppress("UNUSED_PARAMETER") mySpectatingStreamKey: String?,
    ): Comparator<StoreVoiceParticipants.VoiceUser> = Comparator { first, second ->
        fun rank(user: StoreVoiceParticipants.VoiceUser): Int = when {
            user.applicationStream != null -> 0
            user.voiceState?.selfVideo == true -> 1
            else -> 2
        }
        val rank = rank(first) - rank(second)
        if (rank != 0) rank else UserUtils.compareUserNames(
            first.user,
            second.user,
            first.nickname,
            second.nickname,
        )
    }

    private fun createVideoGridEntriesForParticipant(
        participant: StoreVoiceParticipants.VoiceUser,
        channelId: Long,
        activeApplicationStream: StoreApplicationStreaming.ActiveApplicationStream?,
        streamQuality: RtcConnection.Quality?,
        selectedVideoInputDevice: VideoInputDeviceDescription?,
        showLabels: Boolean,
    ): List<CallParticipant> {
        if (!participant.isConnected && !participant.isRinging) return emptyList()
        val result = mutableListOf<CallParticipant>()
        result += CallParticipant.UserOrStreamParticipant(
            VideoCallParticipantView.ParticipantData(
                voiceParticipant = participant,
                mirror = participant.isMe && selectedVideoInputDevice?.facing == VideoInputDeviceFacing.Front,
                scalingType = null,
                insetScalingType = null,
                applicationStreamState = null,
                type = VideoCallParticipantView.ParticipantData.Type.DEFAULT,
                showLabel = showLabels,
                isFullScreen = false,
                streamQuality = null,
            ),
        )

        val participantStream = participant.applicationStream
        val selfStream = participant.voiceState?.selfStream == true
        val streamData = when {
            participantStream != null && selfStream && participantStream.channelId == channelId ->
                applicationStreamParticipant(
                    participant,
                    when (participantStream.encodedStreamKey) {
                        activeApplicationStream?.stream?.encodedStreamKey ->
                            activeApplicationStream.state.toParticipantState()
                        pendingStreamKey -> VideoCallParticipantView.ParticipantData.ApplicationStreamState.CONNECTING
                        else -> VideoCallParticipantView.ParticipantData.ApplicationStreamState.INACTIVE
                    },
                    showLabels,
                )
            activeApplicationStream?.stream?.ownerId == participant.user.id &&
                isStreamFocused(activeApplicationStream.stream) ->
                applicationStreamParticipant(
                    participant,
                    activeApplicationStream.state.toParticipantState(),
                    showLabels,
                )
            else -> null
        }
        if (streamData != null) result += addStreamQualityMetadata(streamData, streamQuality)
        return result
    }

    private fun applicationStreamParticipant(
        participant: StoreVoiceParticipants.VoiceUser,
        state: VideoCallParticipantView.ParticipantData.ApplicationStreamState,
        showLabel: Boolean,
    ) = CallParticipant.UserOrStreamParticipant(
        VideoCallParticipantView.ParticipantData(
            voiceParticipant = participant,
            mirror = false,
            scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT,
            insetScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT,
            applicationStreamState = state,
            type = VideoCallParticipantView.ParticipantData.Type.APPLICATION_STREAMING,
            showLabel = showLabel,
            isFullScreen = false,
            streamQuality = null,
        ),
    )

    private fun addStreamQualityMetadata(
        participant: CallParticipant,
        quality: RtcConnection.Quality?,
    ): CallParticipant {
        val user = participant as? CallParticipant.UserOrStreamParticipant ?: return participant
        val metadata = user.participantData.voiceParticipant.streams?.applicationStreamMetadata ?: return participant
        val resolution = if (metadata.type == ResolutionType.Source) {
            VideoCallParticipantView.StreamResolution.Source
        } else {
            VideoCallParticipantView.StreamResolution.Fixed(metadata.maxHeight)
        }
        val fps = metadata.maxFrameRate?.let { VideoCallParticipantView.StreamFps(it) }
        return user.copy(
            participantData = user.participantData.copy(
                streamQuality = VideoCallParticipantView.ParticipantData.StreamQuality(
                    isPremium = resolution.isHighDefinition || fps?.isHighFrameRate == true,
                    resolution = resolution,
                    fps = fps,
                    isPoorQuality = quality == RtcConnection.Quality.BAD,
                ),
            ),
        )
    }

    private fun computeVisibleVideoParticipants(): List<CallParticipant> {
        val key = focusedVideoParticipantKey
        if (key != null) {
            val focused = allVideoParticipants.firstOrNull { it.participantFocusKey() == key }
            if (focused != null) {
                if (focused is CallParticipant.UserOrStreamParticipant) {
                    return listOf(
                        focused.copy(
                            participantData = focused.participantData.copy(
                                scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT,
                                insetScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT,
                            ),
                        ),
                    )
                }
                return listOf(focused)
            }
            focusedVideoParticipantKey = null
            stopWatchingStreamIfEnded()
        }
        if (isOneOnOneMeCall(allVideoParticipants)) {
            return listOfNotNull(allVideoParticipants.firstOrNull {
                it is CallParticipant.EmbeddedActivityParticipant ||
                    it is CallParticipant.UserOrStreamParticipant && !it.participantData.voiceParticipant.isMe
            })
        }
        return allVideoParticipants
    }

    private fun computePipParticipant(
        myUserId: Long,
        selectedVideoInputDevice: VideoInputDeviceDescription?,
        isStreaming: Boolean,
        cameraState: CameraState,
    ): CallParticipant.UserOrStreamParticipant? {
        val me = allVideoParticipants.filterIsInstance<CallParticipant.UserOrStreamParticipant>()
            .firstOrNull {
                it.participantData.voiceParticipant.isMe &&
                    it.participantData.voiceParticipant.voiceState?.selfVideo == true
            } ?: return null
        val focusedOnSomeoneElse = focusedVideoParticipantKey != null && focusedVideoParticipantKey != myUserId.toString()
        val shouldShow = isOneOnOneMeCall(allVideoParticipants) || focusedOnSomeoneElse ||
            (isStreaming && cameraState == CameraState.CAMERA_ON)
        if (!shouldShow) return null
        return me.copy(
            participantData = me.participantData.copy(
                mirror = selectedVideoInputDevice?.facing == VideoInputDeviceFacing.Front,
            ),
        )
    }

    private fun outputSelectorState(callModel: CallModel): VoiceControlsOutputSelectorState {
        val active = callModel.audioManagerState.activeAudioDevice
        val more = callModel.audioManagerState.audioDevices.any {
            it.type == com.discord.rtcconnection.audio.DiscordAudioManager.DeviceTypes.BLUETOOTH_HEADSET && it.isAvailable
        }
        return if (more) {
            when (active) {
                com.discord.rtcconnection.audio.DiscordAudioManager.DeviceTypes.SPEAKERPHONE ->
                    VoiceControlsOutputSelectorState.SPEAKER_ON_AND_MORE
                com.discord.rtcconnection.audio.DiscordAudioManager.DeviceTypes.BLUETOOTH_HEADSET ->
                    VoiceControlsOutputSelectorState.BLUETOOTH_ON_AND_MORE
                else -> VoiceControlsOutputSelectorState.SPEAKER_OFF_AND_MORE
            }
        } else if (active == com.discord.rtcconnection.audio.DiscordAudioManager.DeviceTypes.SPEAKERPHONE) {
            VoiceControlsOutputSelectorState.SPEAKER_ON
        } else VoiceControlsOutputSelectorState.SPEAKER_OFF
    }

    private fun negotiateFocusedParticipant(state: ViewState.Valid) {
        val focused = state.visibleVideoParticipants.firstOrNull {
            it.participantFocusKey() == state.focusedParticipantKey
        } as? CallParticipant.UserOrStreamParticipant
        val userId = focused?.participantData?.voiceParticipant?.user?.id
        when (focused?.participantData?.type) {
            VideoCallParticipantView.ParticipantData.Type.DEFAULT -> {
                streamRtcConnectionStore.updateFocusedParticipant(null)
                StoreStream.getRtcConnection().updateFocusedParticipant(userId)
                if (userId != null) mediaSettingsStore.setUserOffScreen(userId, false)
            }
            VideoCallParticipantView.ParticipantData.Type.APPLICATION_STREAMING -> {
                streamRtcConnectionStore.updateFocusedParticipant(userId)
                StoreStream.getRtcConnection().updateFocusedParticipant(null)
            }
            null -> clearFocusedParticipants()
        }
    }

    private fun clearFocusedParticipants() {
        streamRtcConnectionStore.updateFocusedParticipant(null)
        StoreStream.getRtcConnection().updateFocusedParticipant(null)
    }

    @MainThread
    private fun clearFocusedVideoParticipant() {
        val state = viewState as? ViewState.Valid ?: return
        focusedVideoParticipantKey = null
        stopWatchingStreamIfEnded()
        updateViewState(
            state.copy(
                visibleVideoParticipants = computeVisibleVideoParticipants(),
                focusedParticipantKey = null,
                pipParticipant = computePipParticipant(
                    state.callModel.myId,
                    state.callModel.selectedVideoDevice,
                    state.callModel.isStreaming,
                    state.cameraState,
                ),
            ),
        )
    }

    private fun setOffScreenVideoParticipants(visible: List<VideoCallParticipantView.ParticipantData>) {
        val state = viewState as? ViewState.Valid ?: return
        if (!state.stopOffscreenVideo) return
        val visibleIds = visible.mapTo(HashSet()) { it.id }
        computeVisibleVideoParticipants().filterIsInstance<CallParticipant.UserOrStreamParticipant>()
            .map(CallParticipant.UserOrStreamParticipant::participantData)
            .filter { it.type == VideoCallParticipantView.ParticipantData.Type.DEFAULT }
            .forEach { mediaSettingsStore.setUserOffScreen(it.voiceParticipant.user.id, it.id !in visibleIds) }
    }

    private fun updateMyVoiceState(
        channel: Channel,
        suppress: Boolean?,
        requestToSpeakTimestamp: UtcDateTime?,
        finished: () -> Unit,
    ) {
        val api = RestAPI.apiOrNull
        if (api == null || channel.guildId <= 0L) {
            finished()
            return
        }
        api.updateMyVoiceStates(
            channel.guildId,
            RestAPIParams.ChannelVoiceStateUpdate(channel.id, suppress, requestToSpeakTimestamp),
        ).restSubscribeOn(false)
            .ui(this)
            .appSubscribe(
                WidgetCallFullscreenViewModel::class.java,
                onTerminated = finished,
                onNext = {},
            )
    }

    private fun hasVideoPermission(): Boolean {
        val state = mostRecentStoreState as? StoreState.Valid ?: return false
        return videoPermissionsManager.hasVideoPermission(
            state.callModel.channel,
            state.callModel.guild?.afkChannelId,
            state.myPermissions,
        )
    }

    private fun isOneOnOneMeCall(participants: List<CallParticipant>) =
        participants.size == 2 && participants.any {
            it is CallParticipant.UserOrStreamParticipant && it.participantData.voiceParticipant.isMe
        }

    private fun isStreamFocused(stream: ModelApplicationStream) =
        focusedVideoParticipantKey == "${stream.ownerId}--STREAM"

    private fun stopWatchingStream() {
        val state = viewState as? ViewState.Valid ?: return
        state.callModel.activeStream?.stream?.encodedStreamKey?.let(applicationStreamingStore::stopStream)
        state.callModel.activeStream?.stream?.let {
            if (isStreamFocused(it)) {
                focusedVideoParticipantKey = null
                updateViewState(
                    state.copy(
                        visibleVideoParticipants = computeVisibleVideoParticipants(),
                        focusedParticipantKey = null,
                        pipParticipant = computePipParticipant(
                            state.callModel.myId,
                            state.callModel.selectedVideoDevice,
                            state.callModel.isStreaming,
                            state.cameraState,
                        ),
                    ),
                )
            }
        }
    }

    private fun stopWatchingStreamIfEnded() {
        val active = (viewState as? ViewState.Valid)?.callModel?.activeStream ?: return
        if (active.state == StoreApplicationStreaming.ActiveApplicationStream.State.ENDED) stopWatchingStream()
    }

    private fun startTapForwardingJob() {
        cancelTapForwardingJob()
        Observable
            .timer(DOUBLE_TAP_THRESHOLD_MS, TimeUnit.MILLISECONDS, backgroundThreadScheduler)
            .ui(this)
            .appSubscribe(
                WidgetCallFullscreenViewModel::class.java,
                subscriptionHandler = { forwardVideoGridInteractionSubscription = it },
                onNext = {
                    val footerWasIdle = videoPlayerIdleDetectorFooter.isIdle()
                    videoPlayerIdleDetectorFooter.onInteraction(videoPlayerIdleDetectorHeader.isIdle())
                    videoPlayerIdleDetectorHeader.onInteraction(footerWasIdle)
                },
            )
    }

    private fun cancelTapForwardingJob() {
        forwardVideoGridInteractionSubscription?.unsubscribe()
        forwardVideoGridInteractionSubscription = null
    }

    private fun preventIdle() {
        videoPlayerIdleDetectorFooter.onPreventIdle()
        videoPlayerIdleDetectorHeader.onPreventIdle()
    }

    private fun onIdleStateChanged(isIdleFooter: Boolean? = null, isIdleHeader: Boolean? = null) {
        val state = viewState as? ViewState.Valid ?: return
        if (isIdleFooter != null && isIdleFooter != state.isIdleFooter) {
            updateViewState(state.copy(isIdleFooter = isIdleFooter))
            eventSubject.onNext(Event.OnIdleStateChanged(isIdleFooter))
        }
        val current = viewState as? ViewState.Valid ?: return
        if (isIdleHeader != null && isIdleHeader != current.isIdleHeader) {
            updateViewState(current.copy(isIdleHeader = isIdleHeader))
        }
    }

    private fun CallParticipant.participantFocusKey(): String = when (this) {
        is CallParticipant.EmbeddedActivityParticipant -> application.id.toString()
        is CallParticipant.UserOrStreamParticipant -> {
            val id = participantData.voiceParticipant.user.id
            if (participantData.type == VideoCallParticipantView.ParticipantData.Type.APPLICATION_STREAMING) {
                "$id--STREAM"
            } else id.toString()
        }
    }

    private fun StoreApplicationStreaming.ActiveApplicationStream.State.toParticipantState() = when (this) {
        StoreApplicationStreaming.ActiveApplicationStream.State.CONNECTING,
        StoreApplicationStreaming.ActiveApplicationStream.State.RECONNECTING,
        -> VideoCallParticipantView.ParticipantData.ApplicationStreamState.CONNECTING
        StoreApplicationStreaming.ActiveApplicationStream.State.ACTIVE ->
            VideoCallParticipantView.ParticipantData.ApplicationStreamState.ACTIVE
        StoreApplicationStreaming.ActiveApplicationStream.State.ENDED,
        -> VideoCallParticipantView.ParticipantData.ApplicationStreamState.ENDED
        StoreApplicationStreaming.ActiveApplicationStream.State.PAUSED ->
            VideoCallParticipantView.ParticipantData.ApplicationStreamState.PAUSED
        StoreApplicationStreaming.ActiveApplicationStream.State.DENIED_FULL ->
            VideoCallParticipantView.ParticipantData.ApplicationStreamState.INACTIVE
    }

    enum class DisplayMode { GRID, STAGE, PRIVATE_CALL_PARTICIPANTS }

    enum class MenuItem {
        LAUNCH_OVERLAY,
        CHANNEL_SETTINGS,
        VOICE_SETTINGS,
        SWITCH_CAMERA,
        SHOW_PARTICIPANT_LIST,
        TEXT_IN_VOICE,
        ACTIVITIES,
    }

    enum class OverlayStatus { DISABLED, ENABLED }

    sealed class Event {
        data class AccessibilityAnnouncement(@StringRes val messageResId: Int) : Event()
        data class EnqueueCallFeedbackSheet(
            val channelId: Long,
            val rtcConnectionId: String?,
            val mediaSessionId: String?,
            val callDuration: Long,
        ) : Event()
        data class EnqueueStreamFeedbackSheet(
            val streamKey: String,
            val mediaSessionId: String?,
        ) : Event()
        object NavigateToPremiumSettings : Event()
        data class OnIdleStateChanged(val isIdle: Boolean) : Event()
        object RequestStartStream : Event()
        object ShowActivitiesDesktopOnlyDialog : Event()
        data class ShowCameraCapacityDialog(val guildMaxVideoChannelUsers: Int) : Event()
        object ShowGuildVideoAtCapacityDialog : Event()
        object ShowNoScreenSharePermissionDialog : Event()
        object ShowNoVadPermissionDialog : Event()
        object ShowNoVideoPermissionDialog : Event()
        object ShowRequestCameraPermissionsDialog : Event()
        object ShowServerDeafenedDialog : Event()
        object ShowServerMutedDialog : Event()
        object ShowSuppressedDialog : Event()
        data class ShowUserSheet(val userId: Long, val channelId: Long) : Event()
    }

    sealed class StoreState {
        object Invalid : StoreState()
        data class Valid(
            val callModel: CallModel,
            val stageCallModel: StageCallModel?,
            val noiseCancellation: Boolean?,
            val myPermissions: Long?,
            val streamVolume: Float,
            val streamQuality: RtcConnection.Quality?,
            val isScreenReaderEnabled: Boolean,
            val totalMentionsCount: Int,
            val connectivityState: StoreConnectivity.DelayedState,
            val myStageRoles: StageRoles?,
            val stopOffscreenVideo: Boolean,
            val isTextInVoiceChannelSelected: Boolean,
            val channelMentionsCount: Int,
            val unreadsCount: Int,
            val embeddedAppBackgrounds: Map<Long, ApplicationAsset>,
            val overlayEnabled: Boolean,
        ) : StoreState()
    }

    sealed class ViewState {
        object Invalid : ViewState()
        object Uninitialized : ViewState()
        data class Valid(
            val callModel: CallModel,
            val stageCallModel: StageCallModel?,
            val overlayStatus: OverlayStatus,
            val visibleVideoParticipants: List<CallParticipant>,
            val isIdleFooter: Boolean,
            val isIdleHeader: Boolean,
            val isControlFadingDisabled: Boolean,
            val isSwitchCameraButtonVisible: Boolean,
            val cameraState: CameraState,
            val displayMode: DisplayMode,
            val outputSelectorState: VoiceControlsOutputSelectorState,
            val focusedParticipantKey: String?,
            val isAnyoneUsingVideo: Boolean,
            val pipParticipant: CallParticipant.UserOrStreamParticipant?,
            val noiseCancellation: Boolean?,
            val privateCallUserListItems: List<StoreVoiceParticipants.VoiceUser>,
            val showParticipantsHiddenView: Boolean,
            val lockCallScreenControls: Boolean,
            val startedAsVideo: Boolean,
            val perceptualStreamVolume: Float,
            val totalMentionsCount: Int,
            val showLowConnectivityBar: Boolean,
            val stageRoles: StageRoles?,
            val isUpdatingRequestToSpeak: Boolean,
            val isMovingToAudience: Boolean,
            val requestingToSpeakCount: Int,
            val stopOffscreenVideo: Boolean,
            val channelPermissions: Long?,
            val startableEvent: GuildScheduledEvent?,
            val activeEvent: GuildScheduledEvent?,
            val textInVoiceMentionsCount: Int,
            val textInVoiceUnreadsCount: Int,
            val isTextInVoiceChannelSelected: Boolean,
            val allVideoParticipants: Map<String, CallParticipant.UserOrStreamParticipant>,
        ) : ViewState() {
            val menuItems: Set<MenuItem> by lazy {
                buildSet {
                    add(MenuItem.VOICE_SETTINGS)
                    if (PermissionUtils.can(Permission.MANAGE_CHANNELS, callModel.channelPermissions)) {
                        add(MenuItem.CHANNEL_SETTINGS)
                    }
                    if (callModel.isConnected && overlayStatus == OverlayStatus.ENABLED) add(MenuItem.LAUNCH_OVERLAY)
                    if (callModel.selectedVideoDevice != null && callModel.videoDevices.size > 1) add(MenuItem.SWITCH_CAMERA)
                    if (!callModel.channel.isStageVoiceChannel()) add(MenuItem.TEXT_IN_VOICE)
                    if (!callModel.channel.isStageVoiceChannel()) add(MenuItem.SHOW_PARTICIPANT_LIST)
                    if (callModel.canUseActivities()) add(MenuItem.ACTIVITIES)
                }
            }
            val titleText: String? by lazy {
                when {
                    callModel.channel.isStageVoiceChannel() || activeEvent != null -> null
                    !callModel.isStreaming && visibleVideoParticipants.size == 1 && callModel.channel.isPrivate() ->
                        (visibleVideoParticipants.singleOrNull() as? CallParticipant.UserOrStreamParticipant)
                            ?.participantData?.voiceParticipant?.displayName ?: callModel.channel.getDisplayName()
                    else -> callModel.channel.getDisplayName()
                }
            }
            val isStreamFocused = focusedParticipantKey?.endsWith("STREAM") == true
            val isVideoCallGridVisible = visibleVideoParticipants.isNotEmpty() &&
                !callModel.isStreaming && displayMode == DisplayMode.GRID
            val isPushToTalk = callModel.inputMode ==
                com.discord.rtcconnection.mediaengine.MediaEngineConnection.InputMode.PUSH_TO_TALK
            val showFooter = !(isVideoCallGridVisible && isIdleFooter && !lockCallScreenControls)
            val showHeader = !(isVideoCallGridVisible && isIdleHeader && !lockCallScreenControls)
            val showStreamVolume = callModel.activeStream != null && !callModel.isStreaming
            val analyticsVideoLayout: String? by lazy {
                if (displayMode != DisplayMode.GRID) null
                else if (focusedParticipantKey != null) "focus" else "grid"
            }
            val isDeafened = callModel.isDeafenedByAnySource
        }
    }

    private data class ParticipantTap(val participantFocusKey: String, val timestamp: Long)
    private data class ObservationFirst(
        val screenReader: Boolean,
        val totalMentions: Int,
        val connectivity: StoreConnectivity.DelayedState,
        val stageRoles: StageRoles?,
    )
    private data class ObservationSecond(
        val stopOffscreenVideo: Boolean,
        val selectedChannel: Channel?,
        val mentionCounts: Map<Long, Int>,
    )
    private data class ObservationThird(
        val unreadCount: Int,
        val backgrounds: Map<Long, ApplicationAsset>,
        val overlayEnabled: Boolean,
    )

    private companion object {
        const val DOUBLE_TAP_THRESHOLD_MS = 255L
        const val MAX_STREAM_VOLUME = 300f
        const val STOP_OFFSCREEN_VIDEO_EXPERIMENT = "2021-03_stop_offscreen_video_streams"

        fun amplitudeToPerceptual(amplitude: Float, normalizedMax: Float): Float {
            if (amplitude <= 0f) return 0f
            val decibels = log10(amplitude / normalizedMax) * 20f
            return normalizedMax * if (decibels > 0f) decibels / 6f + 1f else (decibels + 50f) / 50f
        }

        fun perceptualToAmplitude(perceptual: Float, normalizedMax: Float): Float {
            if (perceptual <= 0f) return 0f
            val decibels = if (perceptual > normalizedMax) {
                (perceptual - normalizedMax) / normalizedMax * 6f
            } else perceptual / normalizedMax * 50f - 50f
            return normalizedMax * 10f.pow(decibels / 20f)
        }
    }
}
