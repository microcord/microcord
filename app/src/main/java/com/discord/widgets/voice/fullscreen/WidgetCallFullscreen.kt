package com.discord.widgets.voice.fullscreen

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.utilities.user.NicknameUtils
import com.discord.R
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isPrivate
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.app.AppActivity
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.dialogs.CameraCapacityDialog
import com.discord.dialogs.GuildVideoAtCapacityDialog
import com.discord.dialogs.audio.AudioOutputSelectionDialog
import com.discord.stores.StoreStream
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.voice.DiscordOverlayService
import com.discord.views.calls.VideoCallParticipantView
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.settings.WidgetSettingsVoice
import com.discord.widgets.settings.premium.WidgetSettingsPremium
import com.discord.widgets.stage.model.StageCallModel
import com.discord.widgets.stage.sheet.WidgetStageRaisedHandsBottomSheet
import com.discord.widgets.status.WidgetGlobalStatusIndicatorState
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.voice.controls.VoiceControlsSheetView
import com.discord.widgets.voice.controls.WidgetScreenShareNfxSheet
import com.discord.widgets.voice.feedback.call.CallFeedbackSheetNavigator
import com.discord.widgets.voice.feedback.stream.StreamFeedbackSheetNavigator
import com.discord.widgets.voice.fullscreen.grid.VideoCallGridAdapter
import com.discord.widgets.voice.fullscreen.grid.VideoCallGridLayoutManager
import com.discord.widgets.voice.fullscreen.stage.StageCallAdapter
import com.discord.widgets.voice.fullscreen.stage.StageCallBottomSheetManager
import com.discord.widgets.voice.fullscreen.stage.StageCallItem
import com.discord.widgets.voice.model.CallModel
import com.discord.widgets.voice.stream.StreamNavigator
import com.google.android.material.bottomsheet.BottomSheetBehavior

/** The dedicated, activity-backed surface for an active voice, video, stream, or stage call. */
class WidgetCallFullscreen : AppFragment(R.layout.widget_call_fullscreen) {
    private val channelId: Long get() = mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
    private val viewModel: WidgetCallFullscreenViewModel by appViewModels {
        WidgetCallFullscreenViewModel(channelId, streamKey = mostRecentIntent.getStringExtra(EXTRA_STREAM_KEY))
    }

    private lateinit var root: ViewGroup
    private lateinit var videoRecycler: RecyclerView
    private lateinit var stageRecycler: RecyclerView
    private lateinit var participantsHidden: View
    private lateinit var privateCallContainer: RelativeLayout
    private lateinit var privateCallStatusPrimary: TextView
    private lateinit var privateCallStatusSecondary: TextView
    private lateinit var privateCallStatusDuration: TextView
    private lateinit var privateCallConnectivity: TextView
    private lateinit var privateCallGrid: com.discord.widgets.voice.fullscreen.grid.PrivateCallGridView
    private lateinit var privateCallBlurredGrid: com.discord.widgets.voice.fullscreen.grid.PrivateCallBlurredGridView
    private lateinit var streamingActive: View
    private lateinit var audioShareWarning: View
    private lateinit var pip: VideoCallParticipantView
    private lateinit var floatingPtt: View
    private lateinit var controls: VoiceControlsSheetView
    private lateinit var controlsBehavior: BottomSheetBehavior<VoiceControlsSheetView>

    private lateinit var gridAdapter: VideoCallGridAdapter
    private lateinit var gridLayoutManager: VideoCallGridLayoutManager
    private lateinit var stageAdapter: StageCallAdapter
    private lateinit var stageLayoutManager: GridLayoutManager
    private val stageBottomSheetManager = StageCallBottomSheetManager()
    private var stageBottomSheetPending = true
    private var durationTicker: Runnable? = null
    private var lastMenuItems: Set<WidgetCallFullscreenViewModel.MenuItem>? = null

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requireActivity().window.attributes = requireActivity().window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        viewModel.setTargetChannelId(channelId)
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        root = view as ViewGroup
        videoRecycler = view.findViewById(R.id.call_video_recycler)
        stageRecycler = view.findViewById(R.id.call_stage_recycler)
        participantsHidden = view.findViewById(R.id.call_participants_hidden)
        privateCallContainer = view.findViewById(R.id.private_call)
        privateCallStatusPrimary = view.findViewById(R.id.private_call_status_primary)
        privateCallStatusSecondary = view.findViewById(R.id.private_call_status_secondary)
        privateCallStatusDuration = view.findViewById(R.id.private_call_status_duration)
        privateCallConnectivity = view.findViewById(R.id.private_call_connectivity_status_bar)
        privateCallGrid = view.findViewById(R.id.private_call_grid_view)
        privateCallBlurredGrid = view.findViewById(R.id.private_call_blurred_grid_view)
        streamingActive = view.findViewById(R.id.call_streaming_active)
        audioShareWarning = view.findViewById(R.id.call_streaming_audio_share_warning)
        // Capturing another app's audio needs `AudioPlaybackCaptureConfiguration`, which arrived in
        // Android 10. The warning is part of the streaming layout, so without this it greeted every
        // viewer regardless of what they are running.
        audioShareWarning.isVisible = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        pip = view.findViewById(R.id.call_pip)
        floatingPtt = view.findViewById(R.id.call_floating_push_to_talk)
        controls = view.findViewById(R.id.voice_controls_sheet_view)

        setUpVideoGrid()
        setUpStageList()
        controlsBehavior = BottomSheetBehavior.from(controls).apply {
            isGestureInsetBottomIgnored = true
            addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onSlide(bottomSheet: View, slideOffset: Float) = viewModel.stopIdle()
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    controls.handleSheetState(newState)
                    viewModel.handleBottomSheetState(newState)
                    if (newState == BottomSheetBehavior.STATE_EXPANDED) viewModel.stopIdle()
                    else if (newState == BottomSheetBehavior.STATE_COLLAPSED) viewModel.startIdle()
                }
            })
        }
        controls.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateControlsPeekHeight() }

        val ptt = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> viewModel.onPushToTalkPressed(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    viewModel.onPushToTalkPressed(false)
            }
            true
        }
        floatingPtt.setOnTouchListener(ptt)
        controls.setOnPTTListener(ptt)
        participantsHidden.setOnClickListener { viewModel.onEmptyStateTapped() }
        view.findViewById<View>(R.id.call_participants_show_button).setOnClickListener {
            viewModel.onShowParticipantsPressed()
        }
        view.findViewById<View>(R.id.call_stop_streaming_button).setOnClickListener {
            viewModel.stopScreenShare()
        }

        ViewCompat.setAccessibilityDelegate(root, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onRequestSendAccessibilityEvent(
                host: ViewGroup,
                child: View,
                event: AccessibilityEvent,
            ): Boolean {
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                ) viewModel.disableControlFading()
                return super.onRequestSendAccessibilityEvent(host, child, event)
            }
        })
        ViewCompat.setOnApplyWindowInsetsListener(controls) { controlView, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            controlView.setPadding(
                controlView.paddingLeft,
                controlView.paddingTop,
                controlView.paddingRight,
                resources.getDimensionPixelSize(R.dimen.vertical_safe_margin) + bars.bottom,
            )
            updateControlsPeekHeight(bars.bottom)
            insets
        }

        setActionBarDisplayHomeAsUpEnabled(true, iconAccessibilityLabel = R.string.back)
        appActivity?.toolbar?.setNavigationOnClickListener { finishActivity(disconnect = false) }
        setOnBackPressed(handler = {
            finishActivity(disconnect = false)
            true
        })
        setOnNewIntentListener { newIntent ->
            viewModel.setTargetChannelId(newIntent.getLongExtra(EXTRA_CHANNEL_ID, -1L))
        }

        val channel = StoreStream.getChannels().getChannel(channelId)
        if (mostRecentIntent.getBooleanExtra(EXTRA_CONNECT_ON_LAUNCH, false)) {
            if (channel?.type == com.discord.api.channel.Channel.GUILD_STAGE_VOICE) {
                viewModel.tryConnectToVoice()
            } else {
                requestMicrophone(
                    onFailure = { finishActivity(disconnect = false) },
                    onSuccess = { viewModel.tryConnectToVoice() },
                )
            }
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        WidgetGlobalStatusIndicatorState.Provider.get().updateState(isViewingCall = true)
        viewModel.observeViewState().bindToComponentLifecycle(this).subscribe(::configureUi)
        viewModel.observeEvents().bindToComponentLifecycle(this).subscribe(::handleEvent)
    }

    override fun onPause() {
        WidgetGlobalStatusIndicatorState.Provider.get().updateState(isViewingCall = false)
        super.onPause()
    }

    override fun onDestroyView() {
        durationTicker?.let(privateCallStatusDuration::removeCallbacks)
        durationTicker = null
        pip.configure(null)
        for (index in 0 until videoRecycler.childCount) {
            (videoRecycler.getChildAt(index) as? VideoCallParticipantView)?.configure(null)
        }
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroyView()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        StreamNavigator.handleActivityResult(requestCode, resultCode, data, viewModel::startScreenShare)
    }

    private fun setUpVideoGrid() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        gridAdapter = VideoCallGridAdapter(
            viewModel::onGridParticipantTapped,
            viewModel::onGridParticipantLongPressed,
            viewModel::targetAndFocusStream,
            viewModel::onStreamQualityIndicatorShown,
            viewModel::onStreamQualityIndicatorClicked,
            VIDEO_GRID_SPAN_COUNT,
        ).apply { setHasStableIds(true) }
        gridLayoutManager = VideoCallGridLayoutManager(
            recyclerviewSizeProvider = { if (landscape) videoRecycler.width else videoRecycler.height },
            spanCount = VIDEO_GRID_SPAN_COUNT,
            onLayoutCompletedEvent = { manager, _ ->
                viewModel.updateOffScreenParticipantsFromScroll(visibleParticipants(manager))
            },
            orientation = if (landscape) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL,
            context = requireContext(),
        ).apply { spanSizeLookup = gridAdapter.spanSizeLookup }
        videoRecycler.layoutManager = gridLayoutManager
        videoRecycler.adapter = gridAdapter
        videoRecycler.itemAnimator = null
        videoRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    viewModel.updateOffScreenParticipantsFromScroll(visibleParticipants(gridLayoutManager))
                }
            }
        })
    }

    private fun setUpStageList() {
        stageAdapter = StageCallAdapter(
            stageRecycler,
            parentFragmentManager,
            viewModel::onGridParticipantTapped,
            viewModel::onGridParticipantLongPressed,
            viewModel::targetAndFocusStream,
            viewModel::onStreamQualityIndicatorShown,
            viewModel::onStreamQualityIndicatorClicked,
        )
        stageLayoutManager = GridLayoutManager(requireContext(), StageCallAdapter.SPAN_COUNT).apply {
            spanSizeLookup = stageAdapter.spanSizeLookup
        }
        stageRecycler.layoutManager = stageLayoutManager
        stageRecycler.adapter = stageAdapter
        stageRecycler.addItemDecoration(stageAdapter.itemDecoration)
    }

    private fun visibleParticipants(manager: VideoCallGridLayoutManager): List<VideoCallParticipantView.ParticipantData> {
        val first = manager.findFirstVisibleItemPosition()
        val last = manager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return emptyList()
        return (first..last).mapNotNull { position ->
            (manager.findViewByPosition(position) as? VideoCallParticipantView)?.data
        }
    }

    private fun configureUi(state: WidgetCallFullscreenViewModel.ViewState) {
        when (state) {
            WidgetCallFullscreenViewModel.ViewState.Invalid -> requireActivity().finish()
            WidgetCallFullscreenViewModel.ViewState.Uninitialized -> Unit
            is WidgetCallFullscreenViewModel.ViewState.Valid -> configureValidUi(state)
        }
    }

    private fun configureValidUi(state: WidgetCallFullscreenViewModel.ViewState.Valid) {
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        configureControls(state)
        configureMenu(state)
        setActionBarTitle(state.titleText.orEmpty())
        setActionBarTitleColor(android.graphics.Color.WHITE)
        root.setBackgroundResource(R.color.primary_dark_900)
        when (state.displayMode) {
            WidgetCallFullscreenViewModel.DisplayMode.GRID -> configureGrid(state)
            WidgetCallFullscreenViewModel.DisplayMode.STAGE -> configureStage(state)
            WidgetCallFullscreenViewModel.DisplayMode.PRIVATE_CALL_PARTICIPANTS -> configurePrivateCall(state)
        }
    }

    private fun configureControls(state: WidgetCallFullscreenViewModel.ViewState.Valid) {
        controls.isVisible = true
        controls.configureUI(
            model = state.callModel,
            outputSelectorState = state.outputSelectorState,
            showVideoButton = state.cameraState != com.discord.widgets.voice.model.CameraState.CAMERA_DISABLED,
            isPushToTalk = state.isPushToTalk,
            isDeafened = state.isDeafened,
            startedAsVideo = state.startedAsVideo,
            isStreamFocused = state.isStreamFocused,
            showDisconnect = !state.isStreamFocused,
            onStopWatchingClick = viewModel::requestStopWatchingStreamFromUserInput,
            onDisconnectClick = { finishActivity() },
            onAudioOutputClick = { AudioOutputSelectionDialog.show(parentFragmentManager) },
            onVideoClick = { requestVideoCallPermissions(viewModel::toggleCameraPressed) },
            onMuteClick = viewModel::onMuteClicked,
            onInviteClick = {
                com.discord.widgets.voice.sheet.launchChannelInvite(this, state.callModel.channel, "Voice Call")
            },
            onScreenshareClick = {
                if (WidgetScreenShareNfxSheet.canShow()) {
                    WidgetScreenShareNfxSheet.show(parentFragmentManager, channelId, state.callModel.channel.guildId)
                } else viewModel.onScreenShareClick()
            },
            onSoundboardClick = {
                com.discord.widgets.voice.sheet.WidgetSoundboardBottomSheet.show(
                    parentFragmentManager,
                    state.callModel.channel.id,
                    state.callModel.channel.guildId,
                )
            },
            showStreamVolume = state.showStreamVolume,
            streamVolume = state.perceptualStreamVolume,
            onStreamVolumeChange = viewModel::onStreamPerceptualVolumeChanged,
            onDeafenPressed = viewModel::onDeafenPressed,
            requestingToSpeakCount = state.requestingToSpeakCount,
            onRaiseHandClick = {
                if (state.stageRoles?.isModerator == true) {
                    WidgetStageRaisedHandsBottomSheet.show(parentFragmentManager, channelId)
                } else viewModel.toggleRequestToSpeak()
            },
            onMoveToAudienceClick = viewModel::moveMeToAudience,
            isUpdatingRequestToSpeak = state.isUpdatingRequestToSpeak,
            stageRoles = state.stageRoles,
        )
        if (state.isVideoCallGridVisible && !state.showFooter) hideControls() else showControls()
        floatingPtt.isVisible = state.isPushToTalk && !controls.isVisible
    }

    private fun configureGrid(state: WidgetCallFullscreenViewModel.ViewState.Valid) {
        privateCallContainer.isVisible = false
        stageRecycler.isVisible = false
        participantsHidden.isVisible = state.showParticipantsHiddenView
        streamingActive.isVisible = state.callModel.isStreaming
        videoRecycler.isVisible = state.isVideoCallGridVisible
        pip.isVisible = state.pipParticipant != null
        pip.configure(state.pipParticipant?.participantData)
        gridAdapter.setData(state.visibleVideoParticipants)
    }

    private fun configurePrivateCall(state: WidgetCallFullscreenViewModel.ViewState.Valid) {
        videoRecycler.isVisible = false
        stageRecycler.isVisible = false
        streamingActive.isVisible = false
        pip.isVisible = false
        participantsHidden.isVisible = state.showParticipantsHiddenView
        privateCallContainer.isVisible = true
        privateCallGrid.configure(state.privateCallUserListItems)
        privateCallBlurredGrid.configure(state.privateCallUserListItems)
        privateCallConnectivity.isVisible = state.showLowConnectivityBar
        privateCallStatusPrimary.text = if (state.callModel.channel.isPrivate()) {
            state.callModel.dmRecipient
                ?.let { NicknameUtils.getName(null, state.callModel.channel.id, it.user) }
                ?: state.callModel.channel.getDisplayName()
        } else state.callModel.channel.getDisplayName()
        privateCallStatusSecondary.setText(if (state.callModel.isConnected) R.string.ongoing_call else R.string.voice_status_not_connected)
        startDurationTicker(state.callModel)
    }

    private fun configureStage(state: WidgetCallFullscreenViewModel.ViewState.Valid) {
        val stage = state.stageCallModel ?: return
        videoRecycler.isVisible = false
        privateCallContainer.isVisible = false
        streamingActive.isVisible = false
        pip.isVisible = false
        participantsHidden.isVisible = state.showParticipantsHiddenView
        stageRecycler.isVisible = true
        if (stageBottomSheetPending) {
            stageBottomSheetPending = !stageBottomSheetManager.configureBottomSheet(
                parentFragmentManager,
                state.callModel.channel,
            )
        }
        stageAdapter.setData(stageItems(stage, state), stage.speakerItems.size)
    }

    private fun stageItems(
        stage: StageCallModel,
        state: WidgetCallFullscreenViewModel.ViewState.Valid,
    ): List<StageCallItem> = buildList {
        val instance = stage.stageInstance
        if (instance != null) {
            add(
                StageCallItem.DetailsItem(
                    instance.topic,
                    state.callModel.channel.getDisplayName(),
                    state.callModel.numUsersConnected,
                    stage.numSpeakers,
                    stage.audience.size,
                    stage.numBlockedUsers,
                    instance.privacyLevel == StageInstancePrivacyLevel.PUBLIC,
                ),
            )
        } else {
            add(
                StageCallItem.PreStartDetailsItem(
                    getString(if (stage.myStageRoles.isModerator) R.string.stage_start_prompt_title_moderator else R.string.stage_start_prompt_title),
                    getString(if (stage.myStageRoles.isModerator) R.string.stage_start_prompt_subtitle_mobile_moderator else R.string.stage_start_prompt_subtitle_mobile),
                    stage.numSpeakers,
                    stage.audience.size,
                ),
            )
        }
        addAll(stage.mediaParticipants.mapNotNull { media ->
            state.allVideoParticipants[media.id]?.let { participant ->
                StageCallItem.MediaItem(
                    media.voiceUser,
                    media.channel,
                    media.isModerator,
                    media.isBlocked,
                    media.mediaType,
                    participant,
                )
            }
        })
        addAll(stage.speakerItems)
        if (stage.speakerItems.isNotEmpty() && stage.audience.isNotEmpty()) add(StageCallItem.DividerItem)
        if (stage.audience.isNotEmpty()) {
            add(StageCallItem.AudienceHeaderItem(stage.audience.size))
            addAll(stage.audience)
        }
    }

    private fun configureMenu(state: WidgetCallFullscreenViewModel.ViewState.Valid) {
        if (lastMenuItems == state.menuItems) return
        lastMenuItems = state.menuItems
        setActionBarOptionsMenu(
            R.menu.menu_call_fullscreen,
            onSelected = { item ->
                when (item.itemId) {
                    R.id.menu_call_overlay_launcher -> DiscordOverlayService.launchForConnect(requireContext())
                    R.id.menu_call_switch_camera -> viewModel.switchCameraInputPressed()
                    R.id.menu_call_video_list -> com.discord.widgets.voice.sheet.WidgetVoiceBottomSheet.show(
                        parentFragmentManager,
                        channelId,
                        false,
                        com.discord.widgets.voice.sheet.WidgetVoiceBottomSheet.FeatureContext.FULLSCREEN_CALL,
                    ).onStreamPreviewClickedListener = { viewModel.targetAndFocusStream(it.stream.encodedStreamKey) }
                    R.id.menu_call_voice_settings -> WidgetSettingsVoice.launch(requireContext())
                    R.id.menu_text_in_voice -> openTextInVoice()
                }
            },
            onConfigured = { menu ->
                menu.findItem(R.id.menu_call_overlay_launcher).isVisible =
                    WidgetCallFullscreenViewModel.MenuItem.LAUNCH_OVERLAY in state.menuItems
                menu.findItem(R.id.menu_call_switch_camera).isVisible =
                    WidgetCallFullscreenViewModel.MenuItem.SWITCH_CAMERA in state.menuItems
                menu.findItem(R.id.menu_call_video_list).isVisible =
                    WidgetCallFullscreenViewModel.MenuItem.SHOW_PARTICIPANT_LIST in state.menuItems
                menu.findItem(R.id.menu_call_voice_settings).isVisible =
                    WidgetCallFullscreenViewModel.MenuItem.VOICE_SETTINGS in state.menuItems
                menu.findItem(R.id.menu_text_in_voice).isVisible =
                    WidgetCallFullscreenViewModel.MenuItem.TEXT_IN_VOICE in state.menuItems
            },
        )
    }

    private fun handleEvent(event: WidgetCallFullscreenViewModel.Event) {
        when (event) {
            is WidgetCallFullscreenViewModel.Event.AccessibilityAnnouncement ->
                root.announceForAccessibility(getString(event.messageResId))
            is WidgetCallFullscreenViewModel.Event.EnqueueCallFeedbackSheet ->
                CallFeedbackSheetNavigator.enqueueNotice(
                    event.channelId,
                    event.rtcConnectionId,
                    event.mediaSessionId,
                    event.callDuration,
                )
            is WidgetCallFullscreenViewModel.Event.EnqueueStreamFeedbackSheet ->
                StreamFeedbackSheetNavigator.enqueueNotice(
                    event.streamKey,
                    event.mediaSessionId,
                )
            WidgetCallFullscreenViewModel.Event.NavigateToPremiumSettings ->
                WidgetSettingsPremium.launch(requireContext())
            is WidgetCallFullscreenViewModel.Event.OnIdleStateChanged ->
                if (event.isIdle && controlsBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                    controlsBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                }
            WidgetCallFullscreenViewModel.Event.RequestStartStream -> {
                controlsBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                StreamNavigator.requestStartStream(this)
            }
            WidgetCallFullscreenViewModel.Event.ShowActivitiesDesktopOnlyDialog -> showNotice(
                R.string.embedded_activities_desktop_only_modal_title,
                R.string.embedded_activities_desktop_only_modal_description,
            )
            is WidgetCallFullscreenViewModel.Event.ShowCameraCapacityDialog ->
                CameraCapacityDialog.show(parentFragmentManager, event.guildMaxVideoChannelUsers)
            WidgetCallFullscreenViewModel.Event.ShowGuildVideoAtCapacityDialog ->
                GuildVideoAtCapacityDialog.show(parentFragmentManager)
            WidgetCallFullscreenViewModel.Event.ShowNoScreenSharePermissionDialog -> showNotice(
                R.string.no_video_permission_dialog_title,
                R.string.no_screenshare_permission_dialog_body,
            )
            WidgetCallFullscreenViewModel.Event.ShowNoVadPermissionDialog -> showNotice(
                R.string.vad_permission_title,
                R.string.vad_permission_body,
            )
            WidgetCallFullscreenViewModel.Event.ShowNoVideoPermissionDialog -> showNotice(
                R.string.no_video_permission_dialog_title,
                R.string.no_video_permission_dialog_body,
            )
            WidgetCallFullscreenViewModel.Event.ShowRequestCameraPermissionsDialog ->
                requestVideoCallPermissions(viewModel::onCameraPermissionsGranted)
            WidgetCallFullscreenViewModel.Event.ShowServerDeafenedDialog -> showNotice(
                R.string.server_deafened_dialog_title,
                R.string.server_deafened_dialog_body,
            )
            WidgetCallFullscreenViewModel.Event.ShowServerMutedDialog -> showNotice(
                R.string.server_muted_dialog_title,
                R.string.server_muted_dialog_body,
            )
            WidgetCallFullscreenViewModel.Event.ShowSuppressedDialog -> showNotice(
                R.string.suppressed,
                R.string.suppressed_permission_body,
            )
            is WidgetCallFullscreenViewModel.Event.ShowUserSheet -> WidgetUserSheet.show(
                parentFragmentManager,
                event.userId,
                event.channelId,
                StoreStream.getChannels().getChannel(event.channelId)?.guildId,
                isVoiceContext = true,
            )
        }
    }

    private fun showNotice(title: Int, body: Int) {
        WidgetNoticeDialog.show(
            parentFragmentManager,
            getString(title),
            getString(body),
            getString(R.string.okay),
        )
    }

    private fun startDurationTicker(model: CallModel) {
        durationTicker?.let(privateCallStatusDuration::removeCallbacks)
        durationTicker = object : Runnable {
            override fun run() {
                privateCallStatusDuration.text = TimeUtils.toFriendlyString(model.timeConnectedMs)
                privateCallStatusDuration.postDelayed(this, 1_000L)
            }
        }.also { privateCallStatusDuration.post(it) }
    }

    private fun updateControlsPeekHeight(bottomInset: Int = 0) {
        if (!::controlsBehavior.isInitialized) return
        controlsBehavior.peekHeight = controls.getPeekHeight() + bottomInset
    }

    private fun hideControls() {
        controls.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION).withEndAction {
            controls.visibility = View.INVISIBLE
        }.start()
        gridAdapter.notifyCallControlsVisibilityChanged(false)
        WindowCompat.setDecorFitsSystemWindows(requireActivity().window, false)
    }

    private fun showControls() {
        controls.visibility = View.VISIBLE
        controls.animate().alpha(1f).setDuration(CONTROLS_FADE_DURATION).start()
        gridAdapter.notifyCallControlsVisibilityChanged(true)
        WindowCompat.setDecorFitsSystemWindows(requireActivity().window, true)
    }

    private fun openTextInVoice() {
        viewModel.onTextInVoiceTapped()
        requireActivity().finish()
        requireActivity().overridePendingTransition(R.anim.activity_slide_horizontal_open_in, R.anim.activity_slide_horizontal_open_out)
    }

    private fun finishActivity(disconnect: Boolean = true) {
        viewModel.selectTextChannelAfterFinish()
        if (disconnect) viewModel.disconnect()
        requireActivity().finish()
        requireActivity().overridePendingTransition(0, R.anim.activity_slide_vertical_close_out)
    }

    companion object {
        private const val CONTROLS_FADE_DURATION = 200L
        private const val VIDEO_GRID_SPAN_COUNT = 2
        const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        const val EXTRA_STREAM_KEY = "com.discord.intent.extra.EXTRA_STREAM_KEY"
        const val EXTRA_CONNECT_ON_LAUNCH = "INTENT_EXTRA_CONNECT_ON_LAUNCH"

        @JvmStatic
        @JvmOverloads
        fun launch(
            context: Context,
            channelId: Long,
            connectOnLaunch: Boolean = false,
            streamKey: String? = null,
            animate: Boolean = false,
        ) {
            val intent = Intent(context, AppActivity.Call::class.java)
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .putExtra(EXTRA_STREAM_KEY, streamKey)
                .putExtra(EXTRA_CONNECT_ON_LAUNCH, connectOnLaunch)
            val options = if (animate) {
                ActivityOptionsCompat.makeCustomAnimation(
                    context,
                    R.anim.activity_slide_vertical_open_in,
                    R.anim.activity_fade_open_out,
                ).toBundle()
            } else null
            context.startActivity(intent, options)
        }
    }
}
