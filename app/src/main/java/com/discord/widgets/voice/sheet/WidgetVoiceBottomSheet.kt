package com.discord.widgets.voice.sheet

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.activities.EmbeddedActivityLauncher
import com.discord.api.channel.Channel
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.dialogs.CameraCapacityDialog
import com.discord.dialogs.GuildVideoAtCapacityDialog
import com.discord.dialogs.audio.AudioOutputSelectionDialog
import com.discord.utilities.channel.ChannelSelector
import com.discord.stores.SelectedChannelAnalyticsLocation
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.stores.StoreAudioManagerV2
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.widgets.chat.input.applauncher.AppLauncherEntrypoint
import com.discord.widgets.chat.input.applauncher.WidgetAppLauncher
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.streams.StreamContext
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.widgets.channels.invite.GroupInviteFriendsSheet
import com.discord.widgets.guildscheduledevent.WidgetEndGuildScheduledEventBottomSheet
import com.discord.widgets.guildscheduledevent.WidgetGuildScheduledEventDetailsBottomSheet
import com.discord.widgets.guildscheduledevent.WidgetPreviewGuildScheduledEvent
import com.discord.widgets.guildscheduledevent.toModel
import com.discord.widgets.guilds.invite.WidgetGuildInviteShare
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.notice.WidgetNoticeNuxOverlay
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.voice.controls.AnchoredVoiceControlsView
import com.discord.widgets.voice.model.CameraState
import com.discord.widgets.voice.fullscreen.WidgetCallFullscreen
import com.discord.widgets.voice.stream.StreamNavigator
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import rx.Subscription

/**
 * The sheet that appears when a voice channel is tapped.
 *
 * Before you join it lists who is already in the channel and offers Join / Join with video. Once
 * you are connected it becomes the call's control surface, hosting [AnchoredVoiceControlsView].
 * A call that turns into a video call — or a stage — outgrows the sheet and is handed off to the
 * fullscreen surface via [WidgetCallFullscreen].
 */
class WidgetVoiceBottomSheet : AppBottomSheet() {

    /** Which surface opened the sheet; it decides where a stream-preview tap goes. */
    enum class FeatureContext {
        HOME,
        FULLSCREEN_CALL,
    }

    /** What [WidgetVoiceBottomSheetViewModel] renders. */
    interface ViewState {
        val title: String
        val subtitle: String?
        val channel: Channel
        val showInviteOption: Boolean
        val showActivitiesOption: Boolean
        val isSelfMuted: Boolean
        val isNoiseCancellationActive: Boolean?
        val isDeafened: Boolean
        val centerContent: CenterContent
        val bottomContent: BottomContent?
    }

    /** The participant list, or the "nobody is here yet" illustration. */
    sealed class CenterContent {
        abstract val items: List<CallParticipantsAdapter.ListItem>

        data class ListItems(
            override val items: List<CallParticipantsAdapter.ListItem>,
        ) : CenterContent()

        data class Empty(
            override val items: List<CallParticipantsAdapter.ListItem>,
        ) : CenterContent()
    }

    /** The join buttons, or the in-call controls. */
    sealed class BottomContent {
        data class Connect(val isConnectEnabled: Boolean) : BottomContent()

        data class Controls(
            val inputMode: MediaEngineConnection.InputMode,
            val audioManagerState: StoreAudioManagerV2.State,
            val isMuted: Boolean,
            val cameraState: CameraState,
            val isScreensharing: Boolean,
        ) : BottomContent()
    }

    private val channelId: Long by lazy { argumentsOrDefault.getLong(EXTRA_CHANNEL_ID) }
    private val forwardToFullscreenIfVideoActivated: Boolean by lazy {
        argumentsOrDefault.getBoolean(ARG_FORWARD_TO_FULLSCREEN_IF_VIDEO_ACTIVATED)
    }
    private val featureContext: FeatureContext by lazy {
        argumentsOrDefault.getSerializable(ARG_FEATURE_CONTEXT) as? FeatureContext
            ?: FeatureContext.HOME
    }

    private val viewModel: WidgetVoiceBottomSheetViewModel by appViewModels {
        WidgetVoiceBottomSheetViewModel(channelId, forwardToFullscreenIfVideoActivated)
    }

    /**
     * Where a stream-preview tap goes when the sheet was opened from the fullscreen call. The
     * fullscreen surface installs this so it can retarget its own renderer instead of relaunching.
     */
    var onStreamPreviewClickedListener: (StreamContext) -> Unit = {}

    private lateinit var participantsAdapter: CallParticipantsAdapter
    private var viewModelEventSubscription: Subscription? = null
    private var dismissRequested = false

    private lateinit var root: CoordinatorLayout
    private lateinit var headerTitle: TextView
    private lateinit var headerAudioOutput: ImageView
    private lateinit var headerChat: ImageView
    private lateinit var prejoinMute: ImageView
    private lateinit var prejoinChat: ImageView
    private lateinit var optionsHandle: View
    private lateinit var headerInvite: ImageView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var controls: AnchoredVoiceControlsView
    private lateinit var connectContainer: View
    private lateinit var connectButton: MaterialButton

    override fun getContentViewResId(): Int = R.layout.widget_voice_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setBottomSheetState(BottomSheetBehavior.STATE_EXPANDED)
        controls.setOnPttPressedListener(viewModel::onPttPressed)
        WidgetEndGuildScheduledEventBottomSheet.registerForResult(
            this,
            END_EVENT_REQUEST_KEY,
            viewModel::onDisconnect,
        )
    }

    override fun onResume() {
        super.onResume()
        dismissRequested = false
        participantsAdapter = MGRecyclerAdapter.configure(
            CallParticipantsAdapter(recycler, isCallPreview = false, quantizeUserAvatars = true),
        )
        participantsAdapter.onEventClicked = { event ->
            WidgetGuildScheduledEventDetailsBottomSheet.showForGuild(parentFragmentManager, event.id)
        }
        participantsAdapter.onStartEventClicked = { event ->
            WidgetPreviewGuildScheduledEvent.launch(
                requireContext(),
                event.toModel(),
                WidgetPreviewGuildScheduledEvent.ExistingEventData(
                    event.id,
                    WidgetPreviewGuildScheduledEvent.Action.START_EVENT,
                ),
            )
        }
        // Rows carry a stream preview that appears and disappears, so the recycler must be allowed
        // to resize with its content.
        recycler.setHasFixedSize(false)

        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .subscribe(::configureUI)
        viewModelEventSubscription?.unsubscribe()
        viewModelEventSubscription = viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .subscribe(::handleEvent)
    }

    private fun bindViews(view: View) {
        root = view.findViewById(R.id.voice_bottom_sheet_root)
        headerTitle = view.findViewById(R.id.voice_bottom_sheet_header_title)
        headerInvite = view.findViewById(R.id.voice_bottom_sheet_header_invite)
        headerAudioOutput = view.findViewById(R.id.voice_bottom_sheet_header_audio_output)
        headerChat = view.findViewById(R.id.voice_bottom_sheet_header_chat)
        prejoinMute = view.findViewById(R.id.voice_bottom_sheet_prejoin_mute)
        prejoinChat = view.findViewById(R.id.voice_bottom_sheet_prejoin_chat)
        optionsHandle = view.findViewById(R.id.voice_bottom_sheet_options_handle)
        recycler = view.findViewById(R.id.voice_bottom_sheet_recycler)
        emptyView = view.findViewById(R.id.empty)
        controls = view.findViewById(R.id.voice_bottom_sheet_controls)
        connectContainer = view.findViewById(R.id.voice_bottom_sheet_connect_container)
        connectButton = view.findViewById(R.id.voice_bottom_sheet_connect)
    }

    // ---- rendering -----------------------------------------------------------------------------

    private fun configureUI(viewState: ViewState) {
        headerTitle.text = viewState.title

        headerInvite.visibility = if (viewState.showInviteOption) View.VISIBLE else View.GONE
        headerInvite.setOnClickListener {
            launchChannelInvite(this, viewState.channel, INVITE_ANALYTICS_SOURCE)
        }

        optionsHandle.setOnTouchListener(OptionsDragListener(viewState.channel.id))
        headerTitle.setOnClickListener { showVoiceSettings(viewState.channel.id) }

        headerAudioOutput.setOnClickListener { AudioOutputSelectionDialog.show(parentFragmentManager) }
        listOf(headerChat, prejoinChat).forEach { chat ->
            chat.setOnClickListener { openTextInVoice(viewState.channel) }
        }

        prejoinMute.setImageResource(
            if (viewState.isSelfMuted) R.drawable.ic_mic_muted_grey_24dp else R.drawable.ic_mic_white_24dp,
        )
        prejoinMute.contentDescription = getString(if (viewState.isSelfMuted) R.string.unmute else R.string.mute)
        prejoinMute.setOnClickListener { viewModel.onMutePressed() }

        configureCenterContent(viewState.centerContent, viewState.channel)
        configureBottomContent(viewState.bottomContent, viewState.channel)
    }

    private fun showVoiceSettings(channelId: Long) =
        WidgetVoiceSettingsBottomSheet.show(parentFragmentManager, channelId, VoiceSettingsMode.MENU)

    private fun showVoiceOptions(channelId: Long) =
        WidgetVoiceSettingsBottomSheet.show(parentFragmentManager, channelId, VoiceSettingsMode.OPTIONS)

    /** A tap or an upward drag on the handle pulls the voice options up. */
    private inner class OptionsDragListener(private val channelId: Long) : View.OnTouchListener {
        private var startY = 0f
        private var opened = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    opened = false
                    view.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dragged = startY - event.rawY
                    if (!opened && dragged >= OPTIONS_DRAG_SLOP_DP * resources.displayMetrics.density) {
                        opened = true
                        showVoiceOptions(channelId)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    if (!opened && event.actionMasked == MotionEvent.ACTION_UP) showVoiceOptions(channelId)
                }
            }
            return true
        }
    }

    private fun openTextInVoice(channel: Channel) {
        ChannelSelector.getInstance().selectChannel(
            channel,
            analyticsLocation = SelectedChannelAnalyticsLocation.TEXT_IN_VOICE,
        )
        dismissSheet()
    }

    private fun configureCenterContent(centerContent: CenterContent, channel: Channel) {
        when (centerContent) {
            is CenterContent.ListItems -> {
                emptyView.visibility = View.INVISIBLE
                recycler.visibility = View.VISIBLE
                participantsAdapter.setData(centerContent.items)
                participantsAdapter.onStreamPreviewClicked = ::onStreamPreviewClicked
                participantsAdapter.onVoiceUserClicked = { voiceUser ->
                    onVoiceUserClicked(voiceUser, channel)
                }
                participantsAdapter.onToggleRingingClicked = viewModel::onToggleRingingPressed
                participantsAdapter.onInviteFriendsClicked = {
                    launchChannelInvite(this, channel, INVITE_ANALYTICS_SOURCE)
                }
                participantsAdapter.onEmbeddedActivityClicked = { activity ->
                    val instanceId = StoreStream.getEmbeddedActivities()
                        .getInstancesForChannel(channel.id)[activity.applicationId]?.compositeInstanceId
                    EmbeddedActivityLauncher.launch(
                        requireContext(),
                        activity.applicationId,
                        channel.id,
                        instanceId,
                    )
                }
            }
            is CenterContent.Empty -> {
                emptyView.visibility = View.VISIBLE
                // Event and activity cards can still be present with nobody connected.
                recycler.visibility =
                    if (centerContent.items.isNotEmpty()) View.VISIBLE else View.GONE
                participantsAdapter.setData(centerContent.items)
            }
        }
    }

    private fun configureBottomContent(bottomContent: BottomContent?, channel: Channel) {
        when (bottomContent) {
            null -> {
                controls.visibility = View.GONE
                connectContainer.visibility = View.GONE
                root.setBackgroundColor(ColorCompat.getThemedColor(requireContext(), R.attr.colorBackgroundPrimary))
            }
            is BottomContent.Controls -> {
                controls.visibility = View.VISIBLE
                connectContainer.visibility = View.GONE
                controls.configureUI(
                    inputMode = bottomContent.inputMode,
                    audioManagerState = bottomContent.audioManagerState,
                    isMuted = bottomContent.isMuted,
                    isScreensharing = bottomContent.isScreensharing,
                    cameraState = bottomContent.cameraState,
                    onMutePressed = viewModel::onMutePressed,
                    onScreenSharePressed = viewModel::onScreenSharePressed,
                    onSpeakerButtonPressed = ::onSpeakerButtonPressed,
                    onCameraButtonPressed = viewModel::onCameraButtonPressed,
                    onSoundboardPressed = {
                        WidgetSoundboardBottomSheet.show(parentFragmentManager, channel.id, channel.guildId)
                    },
                    onDisconnectPressed = viewModel::onDisconnectPressed,
                    appComponent = this,
                    fragmentManager = parentFragmentManager,
                    channelId = channelId,
                    guildId = channel.guildId,
                    canUseSoundboard = PermissionUtils.canUseSoundboard(
                        channel,
                        StoreStream.getPermissions().getPermissionsByChannel()[channel.id],
                    ),
                    forwardToFullscreenIfVideoActivated = forwardToFullscreenIfVideoActivated,
                    featureContext = featureContext,
                )
                root.setBackgroundColor(ColorCompat.getThemedColor(requireContext(), R.attr.colorBackgroundSecondary))
            }
            is BottomContent.Connect -> {
                // INVISIBLE, not GONE: the controls row reserves the height the connect row
                // occupies, so the sheet does not jump when the call connects.
                controls.visibility = View.INVISIBLE
                controls.hidePtt()
                connectContainer.visibility = View.VISIBLE

                connectButton.setText(
                    if (bottomContent.isConnectEnabled) R.string.join_voice
                    else R.string.channel_locked_short,
                )
                connectButton.isEnabled = bottomContent.isConnectEnabled
                connectButton.setOnClickListener {
                    requestMicrophone(null) { viewModel.tryConnectToVoice(false) }
                }

                root.setBackgroundColor(ColorCompat.getThemedColor(requireContext(), R.attr.colorBackgroundPrimary))
            }
        }
    }

    // ---- interaction ---------------------------------------------------------------------------

    private fun onVoiceUserClicked(
        voiceUser: StoreVoiceParticipants.VoiceUser,
        channel: Channel,
    ) {
        WidgetUserSheet.show(
            childFragmentManager,
            voiceUser.user.id,
            channel.id,
            channel.guildId,
            isVoiceContext = true,
        )
    }

    private fun onStreamPreviewClicked(streamContext: StreamContext) {
        when (featureContext) {
            // From home the preview opens the fullscreen call, but only once we hold the
            // microphone permission the call itself needs.
            FeatureContext.HOME -> requestMicrophone(null) {
                viewModel.onStreamPreviewClicked(streamContext.stream.encodedStreamKey)
            }
            // From the fullscreen call the owner retargets its own renderer instead.
            FeatureContext.FULLSCREEN_CALL -> {
                dismiss()
                onStreamPreviewClickedListener(streamContext)
            }
        }
    }

    private fun onSpeakerButtonPressed() {
        AudioOutputSelectionDialog.show(parentFragmentManager)
    }

    private fun handleEvent(event: WidgetVoiceBottomSheetViewModel.Event) {
        when (event) {
            is WidgetVoiceBottomSheetViewModel.Event.ShowSuppressedDialog ->
                showNotice(R.string.suppressed, R.string.suppressed_permission_body)
            is WidgetVoiceBottomSheetViewModel.Event.ShowServerMutedDialog ->
                showNotice(R.string.server_muted_dialog_title, R.string.server_muted_dialog_body)
            is WidgetVoiceBottomSheetViewModel.Event.ShowServerDeafenedDialog -> showNotice(
                R.string.server_deafened_dialog_title,
                R.string.server_deafened_dialog_body,
            )
            is WidgetVoiceBottomSheetViewModel.Event.ShowNoVideoPermissionDialog -> showNotice(
                R.string.no_video_permission_dialog_title,
                R.string.no_video_permission_dialog_body,
            )
            is WidgetVoiceBottomSheetViewModel.Event.ShowNoScreenSharePermissionDialog -> showNotice(
                R.string.no_video_permission_dialog_title,
                R.string.no_screenshare_permission_dialog_body,
            )
            is WidgetVoiceBottomSheetViewModel.Event.ShowNoVideoDevicesAvailableToast ->
                AppToast.show(this, R.string.no_video_devices)
            is WidgetVoiceBottomSheetViewModel.Event.ShowToast ->
                AppToast.show(this, event.toastResId)
            is WidgetVoiceBottomSheetViewModel.Event.ShowRequestCameraPermissionsDialog ->
                requestVideoCallPermissions { viewModel.onCameraPermissionsGranted() }
            is WidgetVoiceBottomSheetViewModel.Event.ShowCameraCapacityDialog ->
                CameraCapacityDialog.show(parentFragmentManager, event.maxVideoChannelUsers)
            is WidgetVoiceBottomSheetViewModel.Event.ShowGuildVideoAtCapacityDialog ->
                GuildVideoAtCapacityDialog.show(parentFragmentManager)
            is WidgetVoiceBottomSheetViewModel.Event.ShowNoiseCancellationBottomSheet ->
                WidgetNoiseCancellationBottomSheet.show(this)
            is WidgetVoiceBottomSheetViewModel.Event.ShowOverlayNux ->
                enqueueOverlayNux()
            is WidgetVoiceBottomSheetViewModel.Event.ShowEventEnd -> WidgetEndGuildScheduledEventBottomSheet.show(
                parentFragmentManager,
                END_EVENT_REQUEST_KEY,
                event.guildScheduledEvent.guildId,
                event.guildScheduledEvent.id,
            )
            is WidgetVoiceBottomSheetViewModel.Event.EnqueueCallFeedbackSheet ->
                Navigation.enqueueCallFeedback(
                    event.channelId,
                    event.rtcConnectionId,
                    event.mediaSessionId,
                    event.callDurationMs,
                )
            is WidgetVoiceBottomSheetViewModel.Event.LaunchVideoCall -> {
                // Stop rendering before handing off, or the sheet keeps reacting to state it is
                // about to stop owning.
                viewModelEventSubscription?.unsubscribe()
                dismissSheet()
                WidgetCallFullscreen.launch(requireContext(), event.channelId, streamKey = event.streamKey)
            }
            is WidgetVoiceBottomSheetViewModel.Event.LaunchStageChannel -> {
                viewModelEventSubscription?.unsubscribe()
                dismissSheet()
                WidgetCallFullscreen.launch(requireContext(), event.channelId, animate = true)
            }
            is WidgetVoiceBottomSheetViewModel.Event.AccessibilityAnnouncement ->
                view?.announceForAccessibility(getString(event.announcementResId))
            is WidgetVoiceBottomSheetViewModel.Event.RequestStartStream -> requestStartStream()
            is WidgetVoiceBottomSheetViewModel.Event.Dismiss -> {
                viewModelEventSubscription?.unsubscribe()
                dismissSheet()
            }
            is WidgetVoiceBottomSheetViewModel.Event.ExpandSheet ->
                setBottomSheetState(BottomSheetBehavior.STATE_EXPANDED)
        }
    }

    // ---- screen share --------------------------------------------------------------------------

    /**
     * Both halves of the screen-capture handshake belong to [StreamNavigator] so that this sheet,
     * the NFX sheet and the fullscreen call all use one request code and one place where the
     * consent token reaches the media engine.
     */
    private fun requestStartStream() = StreamNavigator.requestStartStream(this)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        StreamNavigator.handleActivityResult(requestCode, resultCode, data, StreamNavigator::startStream)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun showNotice(titleRes: Int, bodyRes: Int) {
        WidgetNoticeDialog.show(
            fragmentManager = parentFragmentManager,
            headerText = getString(titleRes),
            bodyText = getString(bodyRes),
            okText = getString(R.string.okay),
        )
    }

    private fun enqueueOverlayNux() {
        WidgetNoticeNuxOverlay.enqueue()
    }

    private fun dismissSheet() {
        if (dismissRequested || !isAdded || dialog?.isShowing != true) return
        dismissRequested = true
        dismiss()
    }

    /**
     * Remaining destinations whose reference classes do not exist in the target yet.
     */
    object Navigation {
        @JvmField
        var enqueueCallFeedback: (Long, String?, String?, Long?) -> Unit = { _, _, _, _ -> }

    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val END_EVENT_REQUEST_KEY = "END_EVENT_REQUEST_KEY"
        private const val ARG_FEATURE_CONTEXT = "ARG_FEATURE_CONTEXT"
        private const val ARG_FORWARD_TO_FULLSCREEN_IF_VIDEO_ACTIVATED =
            "ARG_FORWARD_TO_FULLSCREEN_IF_VIDEO_ACTIVATED"
        private const val OPTIONS_DRAG_SLOP_DP = 12
        private const val INVITE_ANALYTICS_SOURCE = "Voice Channel Bottom Sheet"

        fun show(
            fragmentManager: FragmentManager,
            channelId: Long,
            forwardToFullscreenIfVideoActivated: Boolean,
            featureContext: FeatureContext,
        ): WidgetVoiceBottomSheet = WidgetVoiceBottomSheet().apply {
            arguments = Bundle().apply {
                putLong(EXTRA_CHANNEL_ID, channelId)
                putSerializable(ARG_FEATURE_CONTEXT, featureContext)
                putBoolean(
                    ARG_FORWARD_TO_FULLSCREEN_IF_VIDEO_ACTIVATED,
                    forwardToFullscreenIfVideoActivated,
                )
            }
            show(fragmentManager, WidgetVoiceBottomSheet::class.java.simpleName)
        }
    }
}

internal fun launchChannelInvite(fragment: Fragment, channel: Channel, source: String) {
    if (channel.guildId != 0L) {
        WidgetGuildInviteShare.launch(
            fragment.requireContext(),
            fragment.childFragmentManager,
            channel.guildId,
            channel.id,
            source = source,
        )
    } else {
        GroupInviteFriendsSheet.show(fragment.childFragmentManager, channel.id, source)
    }
}
