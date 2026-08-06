package com.discord.widgets.voice.sheet

import androidx.annotation.MainThread
import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.guild.GuildMaxVideoChannelUsers
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.api.permission.Permission
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelApplicationStream
import com.discord.rtcconnection.RtcConnection
import com.discord.stores.StoreApplicationStreamPreviews
import com.discord.stores.StoreCalls
import com.discord.stores.StoreMediaSettings
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserSettings
import com.discord.stores.StoreVoiceChannelSelected
import com.discord.stores.StoreVoiceParticipants
import com.discord.tooltips.FloatingViewManager
import com.discord.tooltips.TooltipManager
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.voice.VoiceChannelJoinability
import com.discord.widgets.voice.VoiceStoreAccess
import com.discord.widgets.voice.fullscreen.ParticipantsListItemGenerator
import com.discord.widgets.voice.model.CallModel
import com.discord.widgets.voice.model.CameraState
import rx.Observable
import rx.subjects.PublishSubject

/**
 * Drives [WidgetVoiceBottomSheet].
 *
 * The sheet has two jobs and this model reflects both: it renders the channel's participants while
 * you are outside the call, and it becomes the call's control surface once you are in it. That
 * split is the [WidgetVoiceBottomSheet.BottomContent] `Connect` / `Controls` distinction.
 *
 * Several actions are refused rather than performed — muting while server-muted, deafening while
 * server-deafened, unmuting while suppressed on a stage — and each refusal emits an [Event] the
 * fragment turns into an explanatory dialog. That is deliberate: the local toggle would otherwise
 * appear to work and be silently overridden by the server on the next voice-state update.
 */
class WidgetVoiceBottomSheetViewModel(
    private val channelId: Long,
    private val forwardToFullscreenIfVideoActivated: Boolean,
    private val selectedVoiceChannelStore: StoreVoiceChannelSelected =
        StoreStream.getVoiceChannelSelected(),
    private val mediaSettingsStore: StoreMediaSettings = StoreStream.getMediaSettings(),
    private val userSettingsStore: StoreUserSettings = StoreStream.getUserSettings(),
    private val callsStore: StoreCalls = StoreStream.getCalls(),
    /**
     * `StoreStream` constructs no [StoreApplicationStreamPreviews] — it needs a REST-backed fetch
     * lambda only the store graph can supply — so this stays optional. Without it the participant
     * rows render normally and only the stream *thumbnails* are absent.
     */
    private val storeApplicationStreamPreviews: StoreApplicationStreamPreviews? = null,
    private val clock: Clock = ClockFactory.get(),
    private val tooltipManager: TooltipManager =
        TooltipManager.Provider.get(FloatingViewManager.Provider.get()),
    storeStateObservable: Observable<StoreState> = observeStoreState(channelId),
) : AppViewModel<WidgetVoiceBottomSheetViewModel.ViewState>() {

    private val eventSubject = PublishSubject.create<Event>()

    /** Stream keys whose preview has already been requested, so a rebind does not refetch. */
    private val fetchedPreviews = mutableSetOf<String>()

    private var mostRecentStoreState: StoreState = StoreState.Invalid

    /**
     * Whether we have *ever* been connected, and whether the call was ever more than a solo one.
     * The call-feedback sheet is only worth showing for a call that actually happened, or for a
     * connection attempt that never succeeded.
     */
    private var wasEverConnected = false
    private var wasEverMultiParticipant = false
    private var wasConnected: Boolean? = null

    init {
        storeStateObservable
            .computationLatest()
            .ui(this)
            .subscribe(::handleStoreState)
    }

    sealed class StoreState {
        object Invalid : StoreState()

        data class Valid(
            val callModel: CallModel,
            val noiseCancellation: Boolean?,
            val myPermissions: Long?,
        ) : StoreState()
    }

    data class ViewState(
        override val title: String,
        override val subtitle: String?,
        override val channel: Channel,
        override val showInviteOption: Boolean,
        override val isNoiseCancellationActive: Boolean?,
        override val isDeafened: Boolean,
        override val centerContent: WidgetVoiceBottomSheet.CenterContent,
        override val bottomContent: WidgetVoiceBottomSheet.BottomContent?,
    ) : WidgetVoiceBottomSheet.ViewState

    /** One-shot effects: dialogs, toasts, navigation and accessibility announcements. */
    sealed class Event {
        object ShowSuppressedDialog : Event()
        object ShowServerMutedDialog : Event()
        object ShowServerDeafenedDialog : Event()
        object ShowNoVideoPermissionDialog : Event()
        object ShowNoVideoDevicesAvailableToast : Event()
        object ShowNoScreenSharePermissionDialog : Event()
        object ShowRequestCameraPermissionsDialog : Event()
        object ShowGuildVideoAtCapacityDialog : Event()
        object ShowOverlayNux : Event()
        object ShowNoiseCancellationBottomSheet : Event()
        object RequestStartStream : Event()
        object ExpandSheet : Event()
        object Dismiss : Event()

        data class ShowCameraCapacityDialog(val maxVideoChannelUsers: Int) : Event()
        data class LaunchVideoCall(val channelId: Long, val streamKey: String?) : Event()
        data class LaunchStageChannel(val channelId: Long) : Event()
        data class ShowToast(@StringRes val toastResId: Int) : Event()
        data class ShowEventEnd(val guildScheduledEvent: GuildScheduledEvent) : Event()
        data class AccessibilityAnnouncement(@StringRes val announcementResId: Int) : Event()

        data class EnqueueCallFeedbackSheet(
            val channelId: Long,
            val rtcConnectionId: String?,
            val mediaSessionId: String?,
            val callDurationMs: Long,
        ) : Event()
    }

    fun observeEvents(): Observable<Event> = eventSubject

    // ---- state ---------------------------------------------------------------------------------

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        mostRecentStoreState = storeState
        if (storeState !is StoreState.Valid) return
        val callModel = storeState.callModel
        val channel = callModel.channel

        // A connected stage channel always belongs to the fullscreen stage surface, never here.
        if (callModel.isConnected && channel.isStageVoiceChannel()) {
            eventSubject.onNext(Event.LaunchStageChannel(channel.id))
        }

        // Once video or text-in-voice is live the sheet is too small to be the call surface.
        val shouldForwardToFullscreen = callModel.isConnected && forwardToFullscreenIfVideoActivated
        if (shouldForwardToFullscreen) {
            eventSubject.onNext(Event.LaunchVideoCall(channel.id, null))
            return
        }

        val mySpectatingStreamKey = callModel.activeStream?.stream?.encodedStreamKey
        val listItems = ParticipantsListItemGenerator.createConnectedListItems(
            voiceParticipants = callModel.participants,
            mySpectatingStreamKey = mySpectatingStreamKey,
            channel = channel,
            callModel = callModel,
            embeddedActivities = callModel.embeddedActivitiesForChannel.values.toList(),
            applications = callModel.applications,
        )

        val centerContent = if (refreshStreams(listItems)) {
            WidgetVoiceBottomSheet.CenterContent.ListItems(listItems)
        } else {
            WidgetVoiceBottomSheet.CenterContent.Empty(listItems)
        }

        val isConnected = callModel.isConnected
        wasEverConnected = wasEverConnected || isConnected
        wasEverMultiParticipant = wasEverMultiParticipant || callModel.numUsersConnected > 1

        // Only announce suppression on the transition into the call, not on every rebind.
        if (wasConnected == false && isConnected && callModel.isSuppressed) {
            eventSubject.onNext(Event.ShowSuppressedDialog)
        }
        wasConnected = isConnected

        val bottomContent = if (isConnected) {
            WidgetVoiceBottomSheet.BottomContent.Controls(
                inputMode = callModel.inputMode,
                audioManagerState = callModel.audioManagerState,
                isMuted = callModel.isMeMutedByAnySource,
                cameraState = callModel.cameraState,
                // Reference oddity, reproduced: the sparkle flag is fed `hasVideoPermission()`
                // rather than a "never seen the screen-share button" signal.
                showScreenShareSparkle = hasVideoPermission(),
                isScreensharing = callModel.isStreaming,
            )
        } else {
            val canJoin =
                callModel.voiceChannelJoinability != VoiceChannelJoinability.PERMISSIONS_MISSING
            WidgetVoiceBottomSheet.BottomContent.Connect(canJoin)
        }

        updateViewState(
            ViewState(
                title = channel.getDisplayName(),
                subtitle = callModel.guild?.name,
                channel = channel,
                // A stage channel invites through its own surface, not through this row.
                showInviteOption = callModel.canInvite() && !channel.isStageVoiceChannel(),
                isNoiseCancellationActive = storeState.noiseCancellation,
                isDeafened = callModel.isDeafenedByAnySource,
                centerContent = centerContent,
                bottomContent = bottomContent,
            ),
        )
    }

    // ---- actions -------------------------------------------------------------------------------

    @MainThread
    fun onMutePressed() {
        val valid = mostRecentStoreState as? StoreState.Valid
        if (valid != null) {
            // Both of these are server-imposed; toggling locally would be undone immediately.
            if (valid.callModel.isSuppressed) {
                eventSubject.onNext(Event.ShowSuppressedDialog)
                return
            }
            if (valid.callModel.isMuted) {
                eventSubject.onNext(Event.ShowServerMutedDialog)
                return
            }
        }
        when (mediaSettingsStore.toggleSelfMuted()) {
            null -> {
                val controls = viewState?.bottomContent
                    as? WidgetVoiceBottomSheet.BottomContent.Controls ?: return
                eventSubject.onNext(
                    Event.AccessibilityAnnouncement(
                        if (controls.isMuted) R.string.voice_channel_unmuted
                        else R.string.voice_channel_muted,
                    ),
                )
            }
            StoreMediaSettings.SelfMuteFailure.CANNOT_USE_VAD ->
                eventSubject.onNext(Event.ShowToast(R.string.vad_permission_small))
        }
    }

    @MainThread
    fun onDeafenPressed() {
        val callModel = (mostRecentStoreState as? StoreState.Valid)?.callModel ?: return
        if (callModel.isServerDeafened) {
            eventSubject.onNext(Event.ShowServerDeafenedDialog)
            return
        }
        mediaSettingsStore.toggleSelfDeafened()
        eventSubject.onNext(
            Event.AccessibilityAnnouncement(
                // Announced from the pre-toggle state, so this reads as the state being left.
                if (callModel.isDeafenedByAnySource) R.string.voice_channel_undeafened
                else R.string.voice_channel_deafened,
            ),
        )
    }

    @MainThread
    fun onNoiseCancellationPressed() {
        val isActive = requireViewState().isNoiseCancellationActive ?: return
        // First press explains the feature instead of toggling it.
        if (tooltipManager.canShowTooltip(NoiseCancellationTooltip, ignoreMaxTooltips = true)) {
            tooltipManager.ackTooltip(NoiseCancellationTooltip)
            eventSubject.onNext(Event.ShowNoiseCancellationBottomSheet)
            return
        }
        mediaSettingsStore.toggleNoiseCancellation()
        eventSubject.onNext(
            Event.ShowToast(
                if (isActive) R.string.noise_cancellation_off else R.string.noise_cancellation_on,
            ),
        )
    }

    @MainThread
    fun onCameraButtonPressed() {
        if (!hasVideoPermission()) {
            eventSubject.onNext(Event.ShowNoVideoPermissionDialog)
            return
        }
        val controls = viewState?.bottomContent as? WidgetVoiceBottomSheet.BottomContent.Controls
        when (controls?.cameraState) {
            CameraState.CAMERA_DISABLED -> return
            CameraState.CAMERA_ON -> {
                VoiceStoreAccess.selectVideoInputDevice(null)
                return
            }
            else -> Unit
        }
        val valid = mostRecentStoreState as? StoreState.Valid ?: return
        val cap = valid.callModel.guildMaxVideoChannelMembers
        if (cap is GuildMaxVideoChannelUsers.Limited &&
            valid.callModel.numUsersConnected > cap.limit
        ) {
            eventSubject.onNext(Event.ShowCameraCapacityDialog(cap.limit))
            return
        }
        eventSubject.onNext(Event.ShowRequestCameraPermissionsDialog)
    }

    @MainThread
    fun onCameraPermissionsGranted() {
        VoiceStoreAccess.selectDefaultVideoDevice()
    }

    @MainThread
    fun onPttPressed(isPressed: Boolean) {
        VoiceStoreAccess.setPttActive(isPressed)
    }

    @MainThread
    fun onScreenSharePressed() {
        val controls = viewState?.bottomContent
            as? WidgetVoiceBottomSheet.BottomContent.Controls ?: return
        when {
            controls.isScreensharing -> stopStream()
            !hasVideoPermission() -> eventSubject.onNext(Event.ShowNoScreenSharePermissionDialog)
            else -> eventSubject.onNext(Event.RequestStartStream)
        }
    }

    @MainThread
    fun onStreamPreviewClicked(streamKey: String) {
        val valid = mostRecentStoreState as? StoreState.Valid ?: return
        if (valid.callModel.voiceChannelJoinability ==
            VoiceChannelJoinability.GUILD_VIDEO_AT_CAPACITY
        ) {
            eventSubject.onNext(Event.ShowGuildVideoAtCapacityDialog)
            return
        }
        val stream = ModelApplicationStream.decodeStreamKey(streamKey)
        eventSubject.onNext(Event.LaunchVideoCall(stream.channelId, streamKey))
    }

    @MainThread
    fun onToggleRingingPressed(voiceUser: StoreVoiceParticipants.VoiceUser) {
        val recipients = listOf(voiceUser.user.id)
        if (voiceUser.isRinging) {
            callsStore.stopRinging(channelId, recipients)
        } else {
            callsStore.ring(channelId, recipients)
        }
    }

    @MainThread
    fun onDisconnectPressed() {
        // Leaving a channel you are hosting a live event in ends the event; confirm first.
        val eventToEnd = getGuildScheduledEventToEnd()
        if (eventToEnd != null) {
            eventSubject.onNext(Event.ShowEventEnd(eventToEnd))
            return
        }
        onDisconnect()
    }

    @MainThread
    fun onDisconnect() {
        selectedVoiceChannelStore.clear()
        eventSubject.onNext(Event.Dismiss)
        val valid = mostRecentStoreState as? StoreState.Valid ?: return
        // Feedback is worth collecting for a real call, or for one that never connected at all.
        if (!wasEverConnected || wasEverMultiParticipant) {
            val metadata: RtcConnection.Metadata? = valid.callModel.rtcConnectionMetadata
            eventSubject.onNext(
                Event.EnqueueCallFeedbackSheet(
                    channelId = channelId,
                    rtcConnectionId = metadata?.rtcConnectionId,
                    mediaSessionId = metadata?.mediaSessionId,
                    callDurationMs = valid.callModel.getCallDurationMs(clock),
                ),
            )
        }
    }

    @MainThread
    fun tryConnectToVoice(withVideo: Boolean) {
        val valid = mostRecentStoreState as? StoreState.Valid ?: return
        if (valid.callModel.voiceChannelJoinability ==
            VoiceChannelJoinability.GUILD_VIDEO_AT_CAPACITY
        ) {
            eventSubject.onNext(Event.ShowGuildVideoAtCapacityDialog)
            return
        }
        if (!userSettingsStore.getIsMobileOverlayEnabled()) {
            eventSubject.onNext(Event.ShowOverlayNux)
        }
        if (!withVideo) {
            joinVoiceChannel(channelId)
            return
        }
        if (!hasVideoPermission()) {
            eventSubject.onNext(Event.ShowNoVideoPermissionDialog)
            return
        }
        // The reference selects the default camera first and only then joins, so the first frame
        // published to the channel already carries video.
        if (VoiceStoreAccess.isInstalled) {
            VoiceStoreAccess.selectDefaultVideoDevice()
            joinVoiceChannel(channelId)
        } else {
            eventSubject.onNext(Event.ShowNoVideoDevicesAvailableToast)
        }
    }

    @MainThread
    private fun joinVoiceChannel(channelId: Long) {
        selectedVoiceChannelStore.selectVoiceChannel(channelId)
        eventSubject.onNext(Event.ExpandSheet)
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * Stands in for `utilities/permissions/VideoPermissionsManager`, which has not been
     * reconstructed. Semantics are the reference's exactly: video is allowed everywhere except the
     * guild's AFK channel, and inside a guild it additionally needs [Permission.STREAM].
     */
    private fun hasVideoPermission(): Boolean {
        val valid = mostRecentStoreState as? StoreState.Valid ?: return false
        val channel = valid.callModel.channel
        val afkChannelId = valid.callModel.guild?.afkChannelId
        val notAfkChannel = afkChannelId == null || channel.id != afkChannelId
        return notAfkChannel &&
            (channel.isPrivate() || PermissionUtils.can(Permission.STREAM, valid.myPermissions))
    }

    /**
     * The live event this user would end by leaving, if any.
     *
     * The reference delegates to `GuildScheduledEventUtilities.getGuildScheduledEventToEndForCall`.
     * That utility does not exist here, so the rule is applied directly: an ACTIVE event bound to
     * this channel, which this user may manage.
     */
    private fun getGuildScheduledEventToEnd(): GuildScheduledEvent? {
        val valid = mostRecentStoreState as? StoreState.Valid ?: return null
        if (!valid.callModel.canManageEvent()) return null
        return valid.callModel.guildScheduledEvents.firstOrNull { event ->
            event.status == GuildScheduledEventStatus.ACTIVE &&
                event.channelId == valid.callModel.channel.id
        }
    }

    /**
     * Screen-share start/stop is owned by `utilities/voice/VoiceEngineServiceController`, which has
     * not been reconstructed. [Event.RequestStartStream] carries the start half out to the fragment
     * (which owns the `MediaProjection` consent flow); the stop half has nowhere to go yet.
     */
    private fun stopStream() {
        val activeStream = (mostRecentStoreState as? StoreState.Valid)
            ?.callModel?.activeStream?.stream ?: return
        StoreStream.getApplicationStreaming().stopStream(activeStream.encodedStreamKey)
    }


    /**
     * Reports whether there is any participant row to show, fetching each stream preview once on
     * the way through. `null` previews store means no thumbnails, but the list still renders.
     */
    private fun refreshStreams(listItems: List<CallParticipantsAdapter.ListItem>): Boolean {
        val previews = storeApplicationStreamPreviews
            ?: return listItems.any { it is CallParticipantsAdapter.ListItem.VoiceUser }
        return ParticipantsListItemGenerator.refreshStreams(listItems, fetchedPreviews, previews)
    }

    companion object {

        /** Channel types whose participants this sheet can render. */
        private val SUPPORTED_CHANNEL_TYPES =
            setOf(Channel.DM, Channel.GROUP_DM, Channel.GUILD_VOICE, Channel.GUILD_STAGE_VOICE)

        fun observeStoreState(
            channelId: Long,
            permissionsStore: StorePermissions = StoreStream.getPermissions(),
        ): Observable<StoreState> = Observable.combineLatest(
            CallModel.get(channelId),
            permissionsStore.observePermissionsForChannel(channelId),
        ) { callModel, myPermissions ->
            if (callModel == null || callModel.channel.type !in SUPPORTED_CHANNEL_TYPES) {
                StoreState.Invalid
            } else {
                StoreState.Valid(
                    callModel = callModel,
                    noiseCancellation = callModel.voiceSettings.noiseProcessing ==
                        StoreMediaSettings.NoiseProcessing.Cancellation,
                    myPermissions = myPermissions,
                )
            }
        }

    }
}
