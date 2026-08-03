package com.discord.widgets.voice.fullscreen

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.user.NsfwAllowance
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.databinding.WidgetCallPreviewFullscreenBinding
import com.discord.dialogs.GuildVideoAtCapacityDialog
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.streams.StreamContext
import com.discord.utilities.view.extensions.setForwardingWindowInsetsListener
import com.discord.widgets.guildscheduledevent.WidgetGuildScheduledEventDetailsBottomSheet
import com.discord.widgets.guildscheduledevent.WidgetPreviewGuildScheduledEvent
import com.discord.widgets.guildscheduledevent.toModel
import com.discord.widgets.home.WidgetHomePanelNsfw
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.notice.WidgetNoticeNuxOverlay
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.voice.sheet.CallParticipantsAdapter
import com.discord.widgets.voice.sheet.launchChannelInvite
import rx.Subscription

class WidgetCallPreviewFullscreen : AppFragment(R.layout.widget_call_preview_fullscreen) {
    private var _binding: WidgetCallPreviewFullscreenBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val voiceChannelId get() = mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
    private val viewModel: WidgetCallPreviewFullscreenViewModel by appViewModels {
        WidgetCallPreviewFullscreenViewModel(voiceChannelId)
    }

    private lateinit var participantsAdapter: CallParticipantsAdapter
    private lateinit var panelNsfw: WidgetHomePanelNsfw
    private var viewModelEventSubscription: Subscription? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetCallPreviewFullscreenBinding.bind(view)
        setActionBarDisplayHomeAsUpEnabled(false)
        configureInsets()
        binding.callFullscreenParent.setForwardingWindowInsetsListener()

        participantsAdapter = MGRecyclerAdapter.configure(
            CallParticipantsAdapter(binding.callPreviewVoiceSheetRecycler, true, true),
        ).apply {
            onEventClicked = { event ->
                WidgetGuildScheduledEventDetailsBottomSheet.showForGuild(parentFragmentManager, event.id)
            }
            onStartEventClicked = { event ->
                WidgetPreviewGuildScheduledEvent.launch(
                    requireContext(),
                    event.toModel(),
                    WidgetPreviewGuildScheduledEvent.ExistingEventData(
                        event.id,
                        WidgetPreviewGuildScheduledEvent.Action.START_EVENT,
                    ),
                )
            }
        }
        panelNsfw = WidgetHomePanelNsfw(this)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .subscribe(::configureUi)
        viewModelEventSubscription = viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .subscribe(::handleEvent)
        setOnBackPressed(handler = {
            finishActivity(selectTextChannel = true, transition = false)
            true
        })
    }

    override fun onDestroyView() {
        viewModelEventSubscription?.unsubscribe()
        viewModelEventSubscription = null
        if (::participantsAdapter.isInitialized) participantsAdapter.dispose()
        _binding = null
        super.onDestroyView()
    }

    private fun configureActionBar(state: WidgetCallPreviewFullscreenViewModel.ViewState) {
        val hasOtherMentions = state.otherChannelsMentionsCount > 0
        binding.callFullscreenMentions.apply {
            text = state.otherChannelsMentionsCount.toString()
            isVisible = hasOtherMentions
        }
        binding.actionBarToolbar.apply {
            navigationIcon = ContextCompat.getDrawable(
                requireContext(),
                if (hasOtherMentions) R.drawable.ic_call_toolbar_stage_minimize_cutout
                else R.drawable.ic_call_toolbar_stage_minimize,
            )
            setNavigationOnClickListener {
                finishActivity(selectTextChannel = true, transition = false)
            }
        }

        val (showTextInVoice, showInvite) = actionBarMenuVisibility(
            state.isConnectEnabled,
            state.isChannelNsfw,
            state.isNsfwUnconsented,
            state.nsfwAllowed,
        )
        setActionBarOptionsMenu(
            R.menu.menu_call_preview_fullscreen,
            onSelected = { item ->
                if (item.itemId == R.id.menu_invite) {
                    launchChannelInvite(this, state.voiceChannel, ANALYTICS_SOURCE)
                }
            },
            onConfigured = { menu ->
                menu.findItem(R.id.menu_invite).isVisible = showInvite
                menu.findItem(R.id.menu_text_in_voice).apply {
                    isVisible = showTextInVoice
                    if (showTextInVoice) configureTextInVoiceAction(actionView, state.channelMentionsCount)
                }
            },
        )
        setActionBarTitle(state.titleText.orEmpty())
        setActionBarTitleColor(Color.WHITE)
        ColorCompat.setStatusBarTranslucent(this)
        ColorCompat.setStatusBarColor(this, Color.TRANSPARENT, true)
    }

    private fun configureTextInVoiceAction(actionView: View?, mentions: Int) {
        actionView ?: return
        val count = actionView.findViewById<TextView>(R.id.text_in_voice_count)
        val icon = actionView.findViewById<ImageView>(R.id.text_in_voice_icon)
        count.isVisible = mentions > 0
        icon.setImageResource(
            if (mentions > 0) R.drawable.ic_text_in_voice_cutout else R.drawable.ic_text_in_voice,
        )
        if (mentions > 0) {
            count.background = ContextCompat.getDrawable(requireContext(), R.drawable.drawable_circle_red)
            count.text = minOf(99, mentions).toString()
            count.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
        }
        actionView.setOnClickListener {
            transitionActivity()
            viewModel.onTextInVoiceTapped()
        }
    }

    private fun configureParticipants(
        participants: WidgetCallPreviewFullscreenViewModel.ParticipantsList,
        channel: Channel,
    ) {
        when (participants) {
            is WidgetCallPreviewFullscreenViewModel.ParticipantsList.ListItems -> {
                binding.empty.root.visibility = View.INVISIBLE
                binding.callPreviewVoiceSheetRecycler.visibility = View.VISIBLE
                participantsAdapter.setData(participants.items)
                participantsAdapter.onStreamPreviewClicked = ::onStreamPreviewClicked
                participantsAdapter.onVoiceUserClicked = { voiceUser ->
                    WidgetUserSheet.show(
                        childFragmentManager,
                        voiceUser.user.id,
                        channel.id,
                        channel.guildId,
                        isVoiceContext = true,
                    )
                }
                participantsAdapter.onInviteFriendsClicked = {
                    launchChannelInvite(this, channel, ANALYTICS_SOURCE)
                }
                participantsAdapter.onEmbeddedActivityClicked = {
                    WidgetNoticeDialog.show(
                        parentFragmentManager,
                        getString(R.string.embedded_activities_desktop_only_modal_title),
                        getString(R.string.embedded_activities_desktop_only_modal_description),
                        getString(R.string.okay),
                    )
                }
            }
            is WidgetCallPreviewFullscreenViewModel.ParticipantsList.Empty -> {
                binding.empty.root.visibility = View.VISIBLE
                binding.callPreviewVoiceSheetRecycler.isVisible = participants.items.isNotEmpty()
                participantsAdapter.setData(participants.items)
            }
        }
    }

    private fun configureUi(state: WidgetCallPreviewFullscreenViewModel.ViewState) {
        configureActionBar(state)
        configureParticipants(state.participantsList, state.voiceChannel)
        binding.callPreviewJoinVoice.apply {
            setText(if (state.isConnectEnabled) R.string.join_voice else R.string.channel_locked_short)
            isEnabled = state.isConnectEnabled
            setOnClickListener { requestMicrophone(null) { viewModel.tryConnectToVoice() } }
        }
        binding.callPreviewJoinMuted.apply {
            setText(if (state.isConnectEnabled) R.string.join_muted else R.string.channel_locked_short)
            isEnabled = state.isConnectEnabled
            setOnClickListener { requestMicrophone(null) { viewModel.tryConnectToVoice(true) } }
        }
        panelNsfw.configureUI(
            state.voiceChannel.guildId,
            state.isChannelNsfw,
            state.isNsfwUnconsented,
            state.nsfwAllowed,
            binding.callPreviewNsfw,
            onToggleNsfw = { gateShowing -> binding.callFullscreenBody.isVisible = !gateShowing },
            onDeny = ::onDenyNsfw,
        )
    }

    private fun configureInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.callPreviewJoinButtons) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (view.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = bars.bottom
                marginStart = bars.left
                marginEnd = bars.right
                view.layoutParams = this
            }
            insets
        }
        listOf(binding.empty.fullscreenCallPreviewEmptyView, binding.callPreviewVoiceSheetRecycler)
            .forEach { target ->
                ViewCompat.setOnApplyWindowInsetsListener(target) { view, insets ->
                    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    (view.layoutParams as ViewGroup.MarginLayoutParams).apply {
                        marginStart = bars.left
                        marginEnd = bars.right
                        view.layoutParams = this
                    }
                    insets
                }
            }
        ViewCompat.setOnApplyWindowInsetsListener(binding.actionBarToolbarLayout) { view, insets ->
            view.setPadding(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                view.paddingBottom,
            )
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.callFullscreenMentions) { view, insets ->
            val tablet = (resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >=
                Configuration.SCREENLAYOUT_SIZE_LARGE
            val (leftDp, topDp) = unreadIndicatorMargins(
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
                tablet,
            )
            (view.layoutParams as ViewGroup.MarginLayoutParams).apply {
                leftMargin = insets.systemWindowInsetLeft + dpToPixels(leftDp)
                topMargin = insets.systemWindowInsetTop + dpToPixels(topDp)
                view.layoutParams = this
            }
            insets
        }
    }

    private fun handleEvent(event: WidgetCallPreviewFullscreenViewModel.Event) {
        when (event) {
            WidgetCallPreviewFullscreenViewModel.Event.ShowGuildVideoAtCapacityDialog ->
                GuildVideoAtCapacityDialog.show(parentFragmentManager)
            WidgetCallPreviewFullscreenViewModel.Event.ShowOverlayNux -> WidgetNoticeNuxOverlay.enqueue()
            is WidgetCallPreviewFullscreenViewModel.Event.LaunchVideoCall -> {
                finishActivity(transition = false)
                WidgetCallFullscreen.launch(
                    requireContext(),
                    event.channelId,
                    streamKey = event.autoTargetStreamKey,
                )
            }
        }
    }

    private fun onDenyNsfw() = finishActivity(selectTextChannel = true, transition = false)

    private fun onStreamPreviewClicked(streamContext: StreamContext) {
        requestMicrophone(null) {
            viewModel.onStreamPreviewClicked(streamContext.stream.encodedStreamKey)
        }
    }

    private fun transitionActivity() {
        finishActivity(transition = false)
        requireActivity().overridePendingTransition(
            R.anim.activity_slide_horizontal_open_in,
            R.anim.activity_slide_horizontal_open_out,
        )
    }

    private fun finishActivity(selectTextChannel: Boolean = false, transition: Boolean = true) {
        if (selectTextChannel) viewModel.selectTextChannelAfterFinish()
        viewModelEventSubscription?.unsubscribe()
        requireActivity().finish()
        if (transition) {
            requireActivity().overridePendingTransition(0, R.anim.activity_slide_vertical_close_out)
        }
    }

    companion object {
        private const val ANALYTICS_SOURCE = "Fullscreen Voice Channel Preview"
        const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"

        internal fun actionBarMenuVisibility(
            isConnectEnabled: Boolean,
            isChannelNsfw: Boolean,
            isNsfwUnconsented: Boolean,
            nsfwAllowed: NsfwAllowance?,
        ): Pair<Boolean, Boolean> {
            val blocked = isChannelNsfw &&
                (isNsfwUnconsented || nsfwAllowed == NsfwAllowance.DISALLOWED)
            return (isConnectEnabled && !blocked) to !blocked
        }

        internal fun unreadIndicatorMargins(landscape: Boolean, tablet: Boolean): Pair<Int, Int> =
            (if (tablet) 40 else 32) to when {
                tablet -> 36
                landscape -> 28
                else -> 32
            }

        @JvmStatic
        @JvmOverloads
        fun launch(context: Context, channelId: Long, animate: Boolean = false) {
            val intent = AppScreenRouter.createScreenIntent(
                context,
                WidgetCallPreviewFullscreen::class.java,
                Intent().putExtra(EXTRA_CHANNEL_ID, channelId),
            )
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
