package com.discord.widgets.home

import android.os.Bundle
import android.view.View
import android.view.ViewStub
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import androidx.fragment.app.commitNow
import androidx.appcompat.widget.Toolbar
import com.discord.R
import com.discord.panels.OverlappingPanelsLayout
import com.discord.panels.PanelState
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.dialogs.WidgetUrgentMessageDialog
import com.discord.widgets.channels.list.WidgetChannelsList
import com.discord.widgets.channels.memberlist.WidgetChannelMembersList
import com.discord.widgets.channels.WidgetChannelOnboarding
import com.discord.widgets.channels.WidgetChannelTopic
import com.discord.widgets.channels.WidgetChannelSidebarActions
import com.discord.widgets.guilds.list.WidgetGuildsList
import com.discord.widgets.guilds.join.WidgetGuildWelcomeSheet
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemCallMessage
import com.discord.widgets.chat.pins.WidgetChannelPinnedMessages
import com.discord.widgets.quickmentions.WidgetQuickMentionsDialog
import com.discord.widgets.search.WidgetSearch
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isAnnouncementChannel
import com.discord.api.channel.isDM
import com.discord.api.channel.isGuildForumChannel
import com.discord.api.channel.isGuildTextChannel
import com.discord.api.channel.isGuildForumPost
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isSystemDM
import com.discord.app.appViewModels
import com.discord.app.appActivityViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.rx.ui
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.toolbar.positionUnreadCountView
import com.discord.utilities.user.UserUtils.hasUnreadUrgentMessages
import com.discord.widgets.chat.input.AppFlexInputViewModel
import com.discord.widgets.guildscheduledevent.WidgetGuildScheduledEventUpsellBottomSheet
import com.discord.widgets.playstation.WidgetPlaystationIntegrationUpsellBottomSheet
import com.discord.widgets.settings.WidgetMuteSettingsSheet
import com.discord.widgets.user.calls.PrivateCallLauncher
import com.discord.widgets.forums.WidgetForumBrowser
import com.discord.widgets.accessibility.AccessibilityDetectionNavigator
import rx.Subscription

class WidgetHome : AppFragment(R.layout.widget_home), WidgetChatList.Host {
    private val viewModel: WidgetHomeViewModel by appViewModels { WidgetHomeViewModel() }
    private val flexInputViewModel: AppFlexInputViewModel by appActivityViewModels { AppFlexInputViewModel() }

    private lateinit var panels: HomePanelsLayout
    private var navigationSubscription: Subscription? = null
    private var selectedChannelSubscription: Subscription? = null
    private var voiceChannelSubscription: Subscription? = null
    private var mentionSubscription: Subscription? = null
    private var friendNicknameSubscription: Subscription? = null
    private var unreadCount = 0
    private var initialChannelShown = false
    private var startPanelState: PanelState = PanelState.Closed
    private var endPanelState: PanelState = PanelState.Closed

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // A restored WidgetChatList needs its container before child restoration advances.
        if (savedInstanceState != null || childFragmentManager.findFragmentById(R.id.widget_chat_list) != null) {
            view.findViewById<ViewStub>(R.id.widget_home_panel_center_chat_stub)?.inflate()
        }
        super.onViewCreated(view, savedInstanceState)
        panels = view.findViewById(R.id.overlapping_panels)
        startPanelState = PanelState.Closed
        endPanelState = PanelState.Closed
        initialChannelShown = savedInstanceState != null
        // Registered here rather than in onViewBound so `panels` is already resolved, and before the
        // child transactions below so this stays behind WidgetTabsHost in the registry.
        setOnBackPressed(::handleBackPressed)
        panels.registerStartPanelStateListeners(
            OverlappingPanelsLayout.PanelStateListener {
                startPanelState = dismissChatInputWhenPanelOpens(startPanelState, it)
                StoreStream.getNavigation().setLeftPanelState(it)
            },
        )
        panels.registerEndPanelStateListeners(
            OverlappingPanelsLayout.PanelStateListener {
                endPanelState = dismissChatInputWhenPanelOpens(endPanelState, it)
                StoreStream.getNavigation().setRightPanelState(it)
            },
        )
        if (childFragmentManager.findFragmentByTag(GUILDS_TAG) == null) {
            childFragmentManager.commitNow { replace(R.id.widget_guilds, WidgetGuildsList(), GUILDS_TAG) }
        }
        if (childFragmentManager.findFragmentByTag(CHANNELS_TAG) == null) {
            childFragmentManager.commitNow { replace(R.id.widget_channels, WidgetChannelsList(), CHANNELS_TAG) }
        }
        if (childFragmentManager.findFragmentByTag(FORUM_TAG) == null) {
            childFragmentManager.commitNow {
                replace(R.id.widget_forum_channel_list, WidgetForumBrowser(), FORUM_TAG)
            }
        }
        if (childFragmentManager.findFragmentByTag(MEMBERS_TAG) == null) {
            childFragmentManager.commitNow { replace(R.id.widget_connected_list, WidgetChannelMembersList(), MEMBERS_TAG) }
        }
        if (childFragmentManager.findFragmentByTag(TOPIC_TAG) == null) {
            childFragmentManager.commitNow { replace(R.id.widget_channel_topic, WidgetChannelTopic(), TOPIC_TAG) }
        }
        if (childFragmentManager.findFragmentByTag(ACTIONS_TAG) == null) {
            childFragmentManager.commitNow {
                replace(R.id.widget_channel_action_bar, WidgetChannelSidebarActions(), ACTIONS_TAG)
            }
        }
        WidgetChannelSidebarActions.onNotifications = ::openMuteSettings
        WidgetChannelSidebarActions.onSearch = ::openSearch
    }

    /**
     * A DM searches only itself; a guild channel searches the whole guild, because that is the scope
     * the search API indexes and the `in:` filter is how you narrow it back down.
     */
    private fun openSearch(channel: Channel) {
        val context = context ?: return
        if (channel.isPrivate()) {
            WidgetSearch.launchForChannel(channel.id, context)
        } else {
            WidgetSearch.launchForGuild(channel.guildId, context)
        }
    }

    override fun onStart() {
        super.onStart()
        val navigation = StoreStream.getNavigation()
        navigationSubscription = navigation.getNavigationPanelAction().subscribe { action ->
            activity?.runOnUiThread { navigation.setNavigationPanelAction(action, panels) }
        }
        selectedChannelSubscription = StoreStream.getChannelsSelected().observeResolvedSelectedChannel().subscribe { selected ->
            activity?.runOnUiThread {
                val channel = selected.channelOrParent
                val surface = centerSurface(selected)
                lockCloseRightPanel(surface != CenterSurface.CHAT)
                panels.setStartPanelUseFullPortraitWidth(surface == CenterSurface.NONE)
                panels.setStartPanelLockState(
                    if (surface == CenterSurface.NONE) OverlappingPanelsLayout.LockState.OPEN
                    else OverlappingPanelsLayout.LockState.UNLOCKED,
                )
                if (surface == CenterSurface.CHAT) ensureChatInflated()
                view?.findViewById<View>(R.id.widget_home_panel_center_chat)?.visibility =
                    if (surface == CenterSurface.CHAT) View.VISIBLE else View.GONE
                view?.findViewById<View>(R.id.widget_forum_channel_list)?.visibility =
                    if (surface == CenterSurface.FORUM) View.VISIBLE else View.GONE
                if (channel != null) {
                    if (surface == CenterSurface.FORUM) {
                        (childFragmentManager.findFragmentByTag(FORUM_TAG) as? WidgetForumBrowser)?.bindToolbar()
                    } else {
                        configureToolbar(channel)
                    }
                    if (!initialChannelShown) {
                        initialChannelShown = true
                        panels.closePanels()
                    }
                }
            }
        }
        voiceChannelSubscription = StoreStream.getVoiceChannelSelected().observeSelectedVoiceChannelId().subscribe {
            activity?.runOnUiThread {
                StoreStream.getChannelsSelected().getSelectedChannel()?.let(::configureToolbar)
            }
        }
        // A DM's title is built from its recipients, so renaming one has to redraw the toolbar.
        friendNicknameSubscription = StoreStream.getUserRelationships().observeNicknames().subscribe {
            activity?.runOnUiThread {
                StoreStream.getChannelsSelected().getSelectedChannel()?.let(::configureToolbar)
            }
        }
        mentionSubscription = StoreStream.getMentions().observeTotalMentions().subscribe { count ->
            activity?.runOnUiThread {
                unreadCount = count
                updateUnreadCount()
            }
        }
    }

    override fun onStop() {
        navigationSubscription?.unsubscribe()
        navigationSubscription = null
        selectedChannelSubscription?.unsubscribe()
        selectedChannelSubscription = null
        voiceChannelSubscription?.unsubscribe()
        voiceChannelSubscription = null
        mentionSubscription?.unsubscribe()
        mentionSubscription = null
        friendNicknameSubscription?.unsubscribe()
        friendNicknameSubscription = null
        super.onStop()
    }

    override fun onDestroyView() {
        WidgetChannelSidebarActions.onNotifications = null
        WidgetChannelSidebarActions.onSearch = null
        super.onDestroyView()
    }

    /**
     * The home surface consumes every back press it is offered, and what it does depends only on
     * which panel is resting in front of the user:
     *
     * - left panel (guilds + channels) already open: there is nowhere further back, so leave the
     *   app without destroying the task;
     * - centre panel (the chat): step back to the left panel, see [handleCenterPanelBack];
     * - right panel (members): close it back to the chat.
     */
    private fun handleBackPressed(): Boolean {
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack()
            return true
        }
        when (panels.getSelectedPanel()) {
            OverlappingPanelsLayout.Panel.START -> requireActivity().moveTaskToBack(true)
            OverlappingPanelsLayout.Panel.CENTER -> handleCenterPanelBack()
            OverlappingPanelsLayout.Panel.END -> panels.closePanels()
        }
        return true
    }

    private fun dismissChatInputWhenPanelOpens(previous: PanelState, current: PanelState): PanelState {
        val opening = current == PanelState.Opening || current == PanelState.Open
        if (opening && previous != PanelState.Opening && previous != PanelState.Open) {
            flexInputViewModel.hideExpressionTray()
            hideKeyboard()
        }
        return current
    }

    /**
     * Back from the chat panel. A peeked channel steps back to the channel it was peeked from;
     * anything else opens the left panel.
     *
     * Re-entering the fullscreen call surface when the selected channel is the vocal channel currently
     * connected remains owned by the call UI. Thread creation is dismissed through [ChannelSelector].
     */
    internal fun handleCenterPanelBack() {
        val resolved = StoreStream.getChannelsSelected().getResolvedSelectedChannel()
        if (resolved is StoreChannelsSelected.ResolvedSelectedChannel.ThreadDraft) {
            ChannelSelector.getInstance().dismissCreateThread()
            return
        }
        val selected = resolved as? StoreChannelsSelected.ResolvedSelectedChannel.Channel
        val peekParent = selected?.peekParent
        if (selected != null && peekParent != null) {
            StoreStream.selectChannel(selected.channel.guildId, peekParent)
            return
        }
        val selectedChannel = selected?.channel
        val parentChannel = selectedChannel?.let { StoreStream.getChannels().findChannelById(it.parentId) }
        if (selectedChannel?.isGuildForumPost(parentChannel) == true) {
            StoreStream.selectChannel(selectedChannel.guildId, selectedChannel.parentId)
            return
        }
        panels.openStartPanel()
    }

    fun getPanelLayout(): PanelLayout = panels

    fun lockCloseRightPanel(locked: Boolean) {
        panels.setEndPanelLockState(
            if (locked) OverlappingPanelsLayout.LockState.CLOSE else OverlappingPanelsLayout.LockState.UNLOCKED,
        )
        if (locked) StoreStream.getNavigation().setRightPanelState(PanelState.Closed)
    }

    private fun ensureChatInflated() {
        if (view?.findViewById<View>(R.id.widget_home_panel_center_chat) != null) return
        view?.findViewById<ViewStub>(R.id.widget_home_panel_center_chat_stub)?.inflate()
    }

    private fun configureToolbar(channel: Channel) {
        val toolbar = view?.findViewById<View>(R.id.widget_home_panel_center_chat)?.findDescendant(Toolbar::class.java)
            ?: return
        appActivity?.setToolbar(toolbar)
        val resolvedSelection = StoreStream.getChannelsSelected().getResolvedSelectedChannel()
        val threadDraft = resolvedSelection as? StoreChannelsSelected.ResolvedSelectedChannel.ThreadDraft
        val isForumDraft = threadDraft?.parentChannel?.isGuildForumChannel() == true
        val parentChannel = StoreStream.getChannels().findChannelById(channel.parentId)
        val isForumPost = channel.isGuildForumPost(parentChannel) ||
            StoreStream.getThreadsActive().getAllForumThreadsById().containsKey(channel.id)
        val title = when {
            isForumDraft -> getString(R.string.new_forum_post_new)
            threadDraft != null -> getString(R.string.create_thread)
            channel.isDM() -> "@ ${channel.getDisplayName()}"
            channel.isGuildTextChannel() || channel.isAnnouncementChannel() -> "# ${channel.getDisplayName()}"
            else -> channel.getDisplayNameOrDefault(requireContext(), includePrefix = false)
        }
        toolbar.title = null
        setActionBarTitle(title, if (isForumPost || isForumDraft) R.drawable.ic_discord_chat_filled_tinted else null)
        setActionBarSubtitle(
            when {
                threadDraft != null -> threadDraft.parentChannel.getDisplayNameOrDefault(requireContext(), false)
                isForumPost -> parentChannel?.getDisplayNameOrDefault(requireContext(), false)
                else -> null
            },
        )
        setActionBarDisplayHomeAsUpEnabled(
            iconRes = if (isForumPost || threadDraft != null) null else R.drawable.ic_menu_24dp,
            iconAccessibilityLabel = if (isForumPost || threadDraft != null) R.string.back else R.string.toggle_drawer,
        )
        toolbar.setNavigationOnClickListener { handleCenterPanelBack() }
        if (toolbar.menu.size() == 0) {
            toolbar.inflateMenu(R.menu.menu_chat_toolbar)
        }
        val callableDm = channel.isPrivate() && !channel.isSystemDM()
        val selectedVoiceChannelId = StoreStream.getVoiceChannelSelected().getSelectedVoiceChannelId()
        val pinState = WidgetHomeHeaderManager.menuState(
            channelType = channel.type,
            isCallConnected = callableDm && selectedVoiceChannelId == channel.id,
            isChannelNsfw = channel.nsfw,
            isNsfwUnconsented = channel.nsfw &&
                !StoreStream.getGuildsNsfw().isGuildNsfwGateAgreed(channel.guildId),
            nsfwAllowed = StoreStream.getUsers().getMe().nsfwAllowance,
        )
        toolbar.menu.findItem(R.id.menu_chat_pins)?.apply {
            isVisible = pinState.pins
            isEnabled = pinState.pinsEnabled
        }
        toolbar.menu.findItem(R.id.menu_chat_start_call)?.isVisible = callableDm && selectedVoiceChannelId == 0L
        toolbar.menu.findItem(R.id.menu_chat_start_video_call)?.isVisible = callableDm && selectedVoiceChannelId == 0L
        toolbar.menu.findItem(R.id.menu_chat_stop_call)?.isVisible = callableDm && selectedVoiceChannelId == channel.id
        toolbar.menu.findItem(R.id.menu_chat_side_panel)?.isVisible = true
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_chat_start_call -> true.also { privateCallLauncher().launchVoiceCall(channel.id) }
                R.id.menu_chat_start_video_call -> true.also { privateCallLauncher().launchVideoCall(channel.id) }
                R.id.menu_chat_stop_call -> true.also {
                    StoreStream.getVoiceChannelSelected().selectVoiceChannel(0L).subscribe({}, {})
                }
                R.id.menu_chat_pins -> true.also {
                    WidgetChannelPinnedMessages.show(requireContext(), channel.id)
                }
                R.id.menu_chat_side_panel -> true.also { panels.openEndPanel() }
                else -> false
            }
        }
        updateUnreadCount()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        AccessibilityDetectionNavigator.enqueueNoticeWhenEnabled(this)
        viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetHome::class.java, onNext = ::handleEvent)
        StoreStream.getUsers().observeMe()
            .filter { it.hasUnreadUrgentMessages }
            .ui(this)
            .appSubscribe(WidgetHome::class.java) { showUrgentMessageDialog() }
    }

    private fun showUrgentMessageDialog() {
        StoreStream.getNotices().requestToShow(
            StoreNotices.Notice(
                name = WidgetUrgentMessageDialog.NOTICE_NAME,
                priority = 0,
                delayPeriodMs = 0L,
                sinceLastPeriodMs = 0L,
            ) { activity ->
                WidgetUrgentMessageDialog.show(activity.supportFragmentManager)
                true
            },
        )
    }

    private fun handleEvent(event: WidgetHomeViewModel.Event) = when (event) {
        WidgetHomeViewModel.Event.OpenLeftPanel -> panels.openStartPanel()
        WidgetHomeViewModel.Event.ClosePanels -> panels.closePanels()
        WidgetHomeViewModel.Event.UnlockLeftPanel ->
            panels.setStartPanelLockState(OverlappingPanelsLayout.LockState.UNLOCKED)
        WidgetHomeViewModel.Event.ShowChannelOnboardingSheet ->
            WidgetChannelOnboarding.show(parentFragmentManager)
        WidgetHomeViewModel.Event.AnimatePeekIn,
        WidgetHomeViewModel.Event.AnimatePeekOut,
        -> Unit
        is WidgetHomeViewModel.Event.ShowGuildWelcomeSheet ->
            WidgetGuildWelcomeSheet.show(parentFragmentManager, event.guildId)
        is WidgetHomeViewModel.Event.ShowGuildEventUpsell ->
            WidgetGuildScheduledEventUpsellBottomSheet.launch(parentFragmentManager, event.guildId)
        WidgetHomeViewModel.Event.ShowPlaystationUpsell ->
            WidgetPlaystationIntegrationUpsellBottomSheet.show(parentFragmentManager)
        is WidgetHomeViewModel.Event.ShowGuildRoleSubscriptionUpsell -> Unit
    }

    private fun updateUnreadCount() {
        val center = view?.findViewById<View>(R.id.widget_home_panel_center_chat) ?: return
        val count = center.findViewById<TextView>(R.id.home_toolbar_unread_count)
            ?: center.findViewById(R.id.unread)
            ?: return
        count.text = unreadCount.takeIf { it > 0 }?.toString()
        count.visibility = if (unreadCount > 0) View.VISIBLE else View.GONE
        count.setOnClickListener { WidgetQuickMentionsDialog.show(parentFragmentManager) }
        center.findDescendant(Toolbar::class.java)?.positionUnreadCountView(count)
    }

    private fun privateCallLauncher() = PrivateCallLauncher(
        appPermissionsRequests = this,
        appComponent = this,
        context = requireContext(),
        fragmentManager = childFragmentManager,
        storeCalls = StoreStream.getCalls(),
        storeVoiceChannelSelected = StoreStream.getVoiceChannelSelected(),
        storeMediaEngine = StoreStream.getMediaEngine(),
    )

    override fun openCallMessage(
        channelId: Long,
        status: WidgetChatListAdapterItemCallMessage.CallStatus,
    ) {
        if (status == WidgetChatListAdapterItemCallMessage.CallStatus.ACTIVE_JOINED) {
            StoreStream.getVoiceChannelSelected().selectVoiceChannel(channelId).subscribe({}, {})
        } else {
            privateCallLauncher().launchVoiceCall(channelId)
        }
    }

    private fun openMuteSettings(channelId: Long) {
        WidgetMuteSettingsSheet.showForChannel(channelId, childFragmentManager)
    }

    private fun <T : View> View.findDescendant(type: Class<T>): T? {
        if (type.isInstance(this)) return type.cast(this)
        if (this !is android.view.ViewGroup) return null
        for (index in 0 until childCount) getChildAt(index).findDescendant(type)?.let { return it }
        return null
    }

    companion object {
        internal enum class CenterSurface { NONE, CHAT, FORUM }

        internal fun centerSurface(selected: StoreChannelsSelected.ResolvedSelectedChannel): CenterSurface = when {
            selected is StoreChannelsSelected.ResolvedSelectedChannel.Channel &&
                selected.channel.isGuildForumChannel() -> CenterSurface.FORUM
            selected.channelOrParent != null -> CenterSurface.CHAT
            else -> CenterSurface.NONE
        }

        private const val GUILDS_TAG = "WidgetGuildsList"
        private const val CHANNELS_TAG = "WidgetChannelsList"
        private const val FORUM_TAG = "WidgetForumBrowser"
        private const val MEMBERS_TAG = "WidgetChannelMembersList"
        private const val TOPIC_TAG = "WidgetChannelTopic"
        private const val ACTIONS_TAG = "WidgetChannelSidebarActions"
    }
}
