package com.discord.widgets.home

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import androidx.fragment.app.commitNow
import com.discord.R
import com.discord.panels.OverlappingPanelsLayout
import com.discord.panels.PanelsChildGestureRegionObserver
import com.discord.panels.PanelState
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.dialogs.WidgetUrgentMessageDialog
import com.discord.databinding.WidgetHomeBinding
import com.discord.databinding.WidgetHomePanelCenterChatBinding
import com.discord.widgets.botuikit.ModalComponent
import com.discord.widgets.channels.list.WidgetChannelsList
import com.discord.widgets.channels.list.WidgetChannelsListItemChannelActions
import com.discord.widgets.channels.memberlist.WidgetChannelMembersList
import com.discord.widgets.channels.WidgetChannelOnboarding
import com.discord.widgets.channels.WidgetChannelTopic
import com.discord.widgets.channels.WidgetChannelSidebarActions
import com.discord.widgets.directories.WidgetDirectoryChannel
import com.discord.widgets.discovery.WidgetGuildDiscovery
import com.discord.widgets.guilds.list.GuildsListNavigation
import com.discord.widgets.guilds.list.WidgetGuildsList
import com.discord.widgets.hubs.HubEmailArgs
import com.discord.widgets.hubs.WidgetHubEmailFlow
import com.discord.widgets.hubs.toArgsIntent
import com.discord.widgets.guilds.join.WidgetGuildWelcomeSheet
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemCallMessage
import com.discord.widgets.chat.pins.WidgetChannelPinnedMessages
import com.discord.widgets.quickmentions.WidgetQuickMentionsDialog
import com.discord.widgets.search.WidgetSearch
import com.discord.api.channel.Channel
import com.discord.api.guild.GuildFeature
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.getDMRecipient
import com.discord.api.channel.isDMStatusVisible
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.api.channel.isGuildForumPost
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isPendingMessageRequestChannel
import com.discord.api.channel.isSystemDM
import com.discord.api.channel.isThread
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.app.appActivityViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.rx.ui
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.channel.guildChannelIcon
import com.discord.utilities.feature.HeaderCustomization
import com.discord.utilities.feature.HeaderCustomization.Action
import com.discord.utilities.feature.HeaderCustomization.Identity
import com.discord.utilities.feature.HeaderCustomization.Surface
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.toolbar.positionUnreadCountView
import com.discord.utilities.user.UserUtils.hasUnreadUrgentMessages
import com.discord.widgets.chat.input.AppFlexInputViewModel
import com.discord.widgets.settings.WidgetMuteSettingsSheet
import com.discord.widgets.user.calls.PrivateCallLauncher
import com.discord.plugins.PluginUiRegistry
import dev.mantikafasi.manticord.plugins.api.PluginRegistration
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.forums.WidgetForumBrowser
import com.discord.widgets.message_requests.WidgetMessageRequests
import com.discord.widgets.nux.WidgetNavigationHelp
import com.discord.widgets.nux.WidgetNewUserOnboarding
import com.discord.widgets.guilds.onboarding.WidgetGuildOnboarding
import com.discord.models.member.GuildMemberFlags
import com.discord.widgets.accessibility.AccessibilityDetectionNavigator
import rx.Subscription
import rx.Observable

class WidgetHome : AppFragment(R.layout.widget_home), WidgetChatList.Host {
    private val viewModel: WidgetHomeViewModel by appViewModels { WidgetHomeViewModel() }
    private val flexInputViewModel: AppFlexInputViewModel by appActivityViewModels { AppFlexInputViewModel() }

    private var _binding: WidgetHomeBinding? = null
    private val binding get() = requireNotNull(_binding)
    private var chatBinding: WidgetHomePanelCenterChatBinding? = null
    private val panels get() = binding.overlappingPanels
    private var navigationSubscription: Subscription? = null
    private var selectedChannelSubscription: Subscription? = null
    private var spoilerGateSubscription: Subscription? = null
    private val spoilerPanel = WidgetHomePanelSpoiler()
    private var activeThreadsSubscription: Subscription? = null
    private var voiceChannelSubscription: Subscription? = null
    private var mentionSubscription: Subscription? = null
    private var friendNicknameSubscription: Subscription? = null
    private var dmPresenceSubscription: Subscription? = null
    private var unreadCount = 0
    private var awaitingInitialSelection = true
    private var gestureRegionsListener: PanelsChildGestureRegionObserver.GestureRegionsListener? = null

    // Provider only holds a WeakReference, so both this screen and the surfaces that register
    // views have to keep the same instance alive themselves.
    private val gestureRegionObserver by lazy { PanelsChildGestureRegionObserver.Provider.get() }
    private var onboardingGuildId: Long? = null
    private var onboardingVisitGuildId: Long = Long.MIN_VALUE
    private var startPanelState: PanelState = PanelState.Closed
    private var endPanelState: PanelState = PanelState.Closed
    private var pluginHeaderRegistration: PluginRegistration? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = WidgetHomeBinding.bind(view)
        awaitingInitialSelection = true
        // A restored WidgetChatList needs its container before child restoration advances.
        if (savedInstanceState != null || childFragmentManager.findFragmentById(R.id.widget_chat_list) != null) {
            ensureChatInflated()
        }
        super.onViewCreated(view, savedInstanceState)
        pluginHeaderRegistration = PluginUiRegistry.addHeaderListener {
            view.post { if (_binding?.root === view) refreshChatToolbar() }
        }
        applyPanelInsets()
        WidgetHomePanelLoading(binding.root).configure(this)
        startPanelState = PanelState.Closed
        endPanelState = PanelState.Closed
        // Registered here rather than in onViewBound so `panels` is already resolved, and before the
        // child transactions below so this stays behind WidgetTabsHost in the registry.
        setOnBackPressed(::handleBackPressed)
        gestureRegionObserver.let { observer ->
            gestureRegionsListener = PanelsChildGestureRegionObserver.GestureRegionsListener(panels::setChildGestureRegions)
            observer.addGestureRegionsUpdateListener(requireNotNull(gestureRegionsListener))
            panels.setChildGestureRegions(observer.getCurrentGestureRegions())
        }
        panels.registerStartPanelStateListeners(
            OverlappingPanelsLayout.PanelStateListener {
                if (it == PanelState.Opening || it == PanelState.Open) ensureStartPanelFragments()
                startPanelState = dismissChatInputWhenPanelOpens(startPanelState, it)
                // Only framed while a panel is behind it to be framed against.
                binding.panelCenter.widgetHomePanelCenterBorder.visibility =
                    if (it == PanelState.Closed) View.GONE else View.VISIBLE
                StoreStream.getNavigation().setLeftPanelState(it)
            },
        )
        panels.registerEndPanelStateListeners(
            OverlappingPanelsLayout.PanelStateListener {
                if (it == PanelState.Opening || it == PanelState.Open) ensureEndPanelFragment()
                endPanelState = dismissChatInputWhenPanelOpens(endPanelState, it)
                StoreStream.getNavigation().setRightPanelState(it)
            },
        )
        // Closed here rather than in the guild list so that panel keeps out of `channels/list`
        // and `hubs`.
        GuildsListNavigation.showChannelActions = WidgetChannelsListItemChannelActions::show
        GuildsListNavigation.showNavigationHelp = WidgetNavigationHelp::show
        GuildsListNavigation.showDiscovery = { context -> WidgetGuildDiscovery.launch(context) }
        val initialSelection = StoreStream.getChannelsSelected().getResolvedSelectedChannel()
        val initialSurface = centerSurface(initialSelection)
        if (savedInstanceState != null || (initialSurface == CenterSurface.NONE && initialSelection.isResolved())) {
            ensureStartPanelFragments()
        } else {
            // Kept off the startup path, but built once the first screen has settled: left until the
            // first swipe, the guild list, channel list and sidebar were all inflated mid-gesture
            // and that first swipe stalled (~100 ms on a release build).
            // ponytail: fixed delay, an idle handler if startup work ever grows past it.
            view.postDelayed({
                if (_binding?.root === view && !childFragmentManager.isStateSaved) ensureStartPanelFragments()
            }, START_PANEL_PREWARM_DELAY_MS)
        }
        if (savedInstanceState != null) ensureEndPanelFragment()
        if (initialSurface == CenterSurface.FORUM) ensureForumFragment()
        if (childFragmentManager.findFragmentByTag(TOPIC_TAG) == null) {
            childFragmentManager.commitNow { replace(R.id.widget_channel_topic, WidgetChannelTopic(), TOPIC_TAG) }
        }
        WidgetChannelSidebarActions.onNotifications = ::openMuteSettings
        WidgetChannelSidebarActions.onSearch = ::openSearch
        WidgetUserSheet.onCall = { channelId, isVideo ->
            privateCallLauncher().let { launcher ->
                if (isVideo) launcher.launchVideoCall(channelId) else launcher.launchVoiceCall(channelId)
            }
        }
    }

    /** The panels take the bottom inset so the gesture bar's strip slides with them. */
    private fun applyPanelInsets() {
        val panelViews = listOf(binding.panelLeft.root, binding.panelCenter.root, binding.panelRight.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { frame, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            frame.updatePadding(top = bars.top)
            panelViews.forEach { it.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom) }
            WindowInsetsCompat.CONSUMED
        }
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
        val mentions = StoreStream.getMentions()
        unreadCount = mentions.getMentionCounts()
            .filterKeys { it !in mentions.getLowImportanceMentionChannels() }.values.sum()
        updateUnreadCount()
        val navigation = StoreStream.getNavigation()
        // Keep main-thread delivery in the subscription so onStop also cancels queued UI work.
        navigationSubscription = navigation.getNavigationPanelAction().ui().subscribe { action ->
            navigation.setNavigationPanelAction(action, panels)
        }
        selectedChannelSubscription = StoreStream.getChannelsSelected().observeResolvedSelectedChannel().ui().subscribe { selected ->
            // A cold start emits `Uninitialized` before the persisted selection resolves. It has
            // no channel, so deciding the surface from it pins the DM list open at full width and
            // the real channel then snaps it shut a frame later. Wait for the resolved value.
            if (!selected.isResolved()) return@subscribe
            val channel = selected.channelOrParent
            val closeStartupPanel = awaitingInitialSelection && channel != null
            awaitingInitialSelection = false
            val surface = centerSurface(selected)
            if (surface == CenterSurface.NONE) ensureStartPanelFragments()
            if (surface == CenterSurface.FORUM) ensureForumFragment()
            if (surface == CenterSurface.DIRECTORY) {
                ensureDirectoryFragment()
                // Built up front: inflating it on the drag's first frame janks the open.
                ensureEndPanelFragment()
            }
            lockCloseRightPanel(surface != CenterSurface.CHAT && surface != CenterSurface.DIRECTORY)
            panels.setStartPanelUseFullPortraitWidth(surface == CenterSurface.NONE)
            panels.setStartPanelLockState(
                if (surface == CenterSurface.NONE) OverlappingPanelsLayout.LockState.OPEN
                else OverlappingPanelsLayout.LockState.UNLOCKED,
            )
            if (surface == CenterSurface.CHAT) ensureChatInflated()
            chatBinding?.root?.visibility = if (surface == CenterSurface.CHAT) View.VISIBLE else View.GONE
            binding.panelCenter.widgetForumChannelList.visibility =
                if (surface == CenterSurface.FORUM) View.VISIBLE else View.GONE
            binding.panelCenter.widgetHomePanelDirectory.visibility =
                if (surface == CenterSurface.DIRECTORY) View.VISIBLE else View.GONE
            if (channel != null) {
                if (surface == CenterSurface.FORUM) {
                    (childFragmentManager.findFragmentByTag(FORUM_TAG) as? WidgetForumBrowser)?.refreshToolbar()
                } else if (surface != CenterSurface.DIRECTORY) {
                    configureToolbar(channel)
                }
                if (closeStartupPanel) panels.closePanelsImmediately()
            }
        }
        activeThreadsSubscription = StoreStream.getThreadsActive().observeAllActiveThreadsById().ui().subscribe {
            refreshChatToolbar()
        }
        voiceChannelSubscription = StoreStream.getVoiceChannelSelected().observeSelectedVoiceChannelId().ui().subscribe {
            refreshChatToolbar()
        }
        // A DM's title is built from its recipients, so renaming one has to redraw the toolbar.
        friendNicknameSubscription = StoreStream.getUserRelationships().observeNicknames().ui().subscribe {
            refreshChatToolbar()
        }
        dmPresenceSubscription = StoreStream.getChannelsSelected().observeResolvedSelectedChannel()
            .switchMap { selected ->
                selected.channelOrParent?.getDMRecipient()?.id
                    ?.let(StoreStream.getPresences()::observePresenceForUser)
                    ?: Observable.just(null)
            }
            .distinctUntilChanged()
            .ui()
            .subscribe { refreshChatToolbar() }
        mentionSubscription = StoreStream.getMentions().observeTotalMentions().ui().subscribe { count ->
            unreadCount = count
            updateUnreadCount()
        }
        val spoilerAgree = StoreStream.getChannelSpoilerAgree()
        spoilerGateSubscription = Observable.combineLatest(
            StoreStream.getChannelsSelected().observeResolvedSelectedChannel(),
            spoilerAgree.observeAnyAgreementChange(),
        ) { selected, _ -> selected }
            .ui()
            .subscribe(::configureSpoilerGate)
    }

    private fun configureSpoilerGate(selected: StoreChannelsSelected.ResolvedSelectedChannel) {
        val chat = chatBinding ?: return
        val channel = selected.channelOrParent?.takeIf { centerSurface(selected) == CenterSurface.CHAT }
        val spoilerAgree = StoreStream.getChannelSpoilerAgree()
        val gatingChannel = spoilerAgree.getSpoilerGatingChannelId(channel)
            ?.let { StoreStream.getChannels().getChannel(it) }
        spoilerPanel.configureUI(
            channel = channel,
            gatingChannel = gatingChannel,
            viewStub = chat.homePanelCenterSpoiler,
            onToggleGate = { gated ->
                chat.widgetChatList.visibility = if (gated) View.GONE else View.VISIBLE
                chat.widgetChatInput.visibility = if (gated) View.GONE else View.VISIBLE
            },
            onAgree = spoilerAgree::agree,
            onDeny = { ChannelSelector.getInstance().selectChannel(0L, 0L) },
        )
    }

    override fun onStop() {
        navigationSubscription?.unsubscribe()
        navigationSubscription = null
        selectedChannelSubscription?.unsubscribe()
        selectedChannelSubscription = null
        spoilerGateSubscription?.unsubscribe()
        spoilerGateSubscription = null
        activeThreadsSubscription?.unsubscribe()
        activeThreadsSubscription = null
        voiceChannelSubscription?.unsubscribe()
        voiceChannelSubscription = null
        mentionSubscription?.unsubscribe()
        mentionSubscription = null
        friendNicknameSubscription?.unsubscribe()
        friendNicknameSubscription = null
        dmPresenceSubscription?.unsubscribe()
        dmPresenceSubscription = null
        super.onStop()
    }

    override fun onDestroyView() {
        chatBinding?.homeToolbar?.menu?.let(WidgetHomeHeaderManager::clearPluginActions)
        pluginHeaderRegistration?.unregister()
        pluginHeaderRegistration = null
        gestureRegionsListener?.let(gestureRegionObserver::removeGestureRegionsUpdateListener)
        gestureRegionsListener = null
        WidgetChannelSidebarActions.onNotifications = null
        WidgetChannelSidebarActions.onSearch = null
        WidgetUserSheet.onCall = null
        chatBinding = null
        _binding = null
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
        if (WidgetSearch.reopenAfterJump(requireContext(), selected?.channel?.id)) return
        val peekParent = selected?.peekParent
        if (selected != null && peekParent != null) {
            StoreStream.selectChannel(selected.channel.guildId, peekParent)
            return
        }
        val selectedChannel = selected?.channel
        if (selectedChannel?.isPendingMessageRequestChannel() == true) {
            returnToMessageRequests()
            return
        }
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
        if (chatBinding != null) return
        chatBinding = WidgetHomePanelCenterChatBinding.bind(
            binding.panelCenter.widgetHomePanelCenterChatStub.inflate(),
        )
    }

    private fun ensureStartPanelFragments() {
        if (childFragmentManager.findFragmentByTag(GUILDS_TAG) == null) {
            childFragmentManager.commitNow { replace(R.id.widget_guilds, WidgetGuildsList(), GUILDS_TAG) }
        }
        if (childFragmentManager.findFragmentByTag(CHANNELS_TAG) == null) {
            childFragmentManager.commitNow { replace(R.id.widget_channels, WidgetChannelsList(), CHANNELS_TAG) }
        }
        if (childFragmentManager.findFragmentByTag(ACTIONS_TAG) == null) {
            childFragmentManager.commitNow {
                replace(R.id.widget_channel_action_bar, WidgetChannelSidebarActions(), ACTIONS_TAG)
            }
        }
    }

    private fun ensureEndPanelFragment() {
        if (childFragmentManager.findFragmentByTag(MEMBERS_TAG) == null) {
            childFragmentManager.commitNow { replace(R.id.widget_connected_list, WidgetChannelMembersList(), MEMBERS_TAG) }
        }
    }

    private fun ensureDirectoryFragment() {
        if (childFragmentManager.findFragmentByTag(DIRECTORY_TAG) == null) {
            childFragmentManager.commitNow {
                replace(R.id.widget_home_panel_directory, WidgetDirectoryChannel(), DIRECTORY_TAG)
            }
        }
    }

    private fun ensureForumFragment() {
        if (childFragmentManager.findFragmentByTag(FORUM_TAG) == null) {
            childFragmentManager.commitNow {
                replace(R.id.widget_forum_channel_list, WidgetForumBrowser(), FORUM_TAG)
            }
        }
        (childFragmentManager.findFragmentByTag(FORUM_TAG) as? WidgetForumBrowser)
            ?.updateHomeUnreadCount(unreadCount)
    }

    private fun refreshChatToolbar() {
        val selected = StoreStream.getChannelsSelected().getResolvedSelectedChannel()
        if (centerSurface(selected) == CenterSurface.CHAT) selected.channelOrParent?.let(::configureToolbar)
    }

    private fun configureToolbar(channel: Channel) {
        val toolbar = chatBinding?.homeToolbar ?: return
        appActivity?.setToolbar(toolbar)
        val resolvedSelection = StoreStream.getChannelsSelected().getResolvedSelectedChannel()
        val threadDraft = resolvedSelection as? StoreChannelsSelected.ResolvedSelectedChannel.ThreadDraft
        val isForumDraft = threadDraft?.parentChannel?.isThreadOnlyChannel() == true
        val parentChannel = StoreStream.getChannels().findChannelById(channel.parentId)
        val isForumPost = channel.isGuildForumPost(parentChannel) ||
            StoreStream.getThreadsActive().getAllForumThreadsById().containsKey(channel.id)
        val isMessageRequest = channel.isPendingMessageRequestChannel()
        val title = when {
            isForumDraft -> getString(R.string.new_forum_post_new)
            threadDraft != null -> getString(R.string.create_thread)
            else -> channel.getDisplayNameOrDefault(requireContext(), includePrefix = false)
        }
        val titleIcon = when {
            isForumPost || isForumDraft -> R.drawable.ic_discord_chat_filled_tinted
            threadDraft != null -> null
            channel.type == Channel.DM -> R.drawable.ic_direct_message_header
            channel.type == Channel.GROUP_DM -> R.drawable.ic_group_message_header
            else -> guildChannelIcon(channel)
        }
        toolbar.title = null
        setActionBarTitle(title, titleIcon)
        when (channel.type) {
            Channel.DM -> channel.getDMRecipient()?.let { IconUtils.getForUser(it, size = 24) }
            Channel.GROUP_DM -> IconUtils.getForChannel(channel, size = 24)
            else -> null
        }?.let { actionBarTitleLayout?.setAvatar(it) }
        setActionBarSubtitle(
            when {
                threadDraft != null -> threadDraft.parentChannel.getDisplayNameOrDefault(requireContext(), false)
                isForumPost -> parentChannel?.getDisplayNameOrDefault(requireContext(), false)
                else -> null
            },
        )
        val isBack = isForumPost || threadDraft != null || isMessageRequest || WidgetSearch.canReopenFrom(channel.id)
        setActionBarDisplayHomeAsUpEnabled(
            iconRes = if (isBack) null else R.drawable.ic_menu_24dp,
            iconAccessibilityLabel = if (isBack) {
                R.string.back
            } else {
                R.string.toggle_drawer
            },
        )
        toolbar.setNavigationOnClickListener {
            if (isMessageRequest) returnToMessageRequests() else handleCenterPanelBack()
        }
        if (toolbar.menu.size() == 0) {
            toolbar.inflateMenu(R.menu.menu_chat_toolbar)
        }
        val visibleActions = WidgetHomeHeaderManager.customizeMenu(
            toolbar.menu,
            requireContext(),
            if (channel.isPrivate()) Surface.DM else Surface.CHANNEL,
        )
        val callableDm = channel.isPrivate() && !channel.isSystemDM()
        val selectedVoiceChannelId = StoreStream.getVoiceChannelSelected().getSelectedVoiceChannelId()
        toolbar.menu.findItem(R.id.menu_chat_start_call)?.isVisible =
            callableDm && selectedVoiceChannelId == 0L && visibleActions[Action.VOICE] != false
        toolbar.menu.findItem(R.id.menu_chat_start_video_call)?.isVisible =
            callableDm && selectedVoiceChannelId == 0L && visibleActions[Action.VIDEO] != false
        toolbar.menu.findItem(R.id.menu_chat_stop_call)?.isVisible =
            callableDm && selectedVoiceChannelId == channel.id && visibleActions[Action.VOICE] != false
        toolbar.menu.findItem(R.id.menu_chat_search)?.isVisible = visibleActions[Action.SEARCH] != false
        toolbar.menu.findItem(R.id.menu_chat_side_panel)?.isVisible = visibleActions[Action.MEMBERS] != false
        toolbar.menu.findItem(R.id.menu_chat_pins)?.isVisible = visibleActions[Action.PINS] != false
        WidgetHomeHeaderManager.configurePluginActions(toolbar.menu, requireContext(), channel)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_chat_start_call -> true.also { privateCallLauncher().launchVoiceCall(channel.id) }
                R.id.menu_chat_start_video_call -> true.also { privateCallLauncher().launchVideoCall(channel.id) }
                R.id.menu_chat_stop_call -> true.also {
                    StoreStream.getVoiceChannelSelected().selectVoiceChannel(0L).subscribe({}, {})
                }
                R.id.menu_chat_search -> true.also { openSearch(channel) }
                R.id.menu_chat_side_panel -> true.also { panels.openEndPanel() }
                R.id.menu_chat_pins -> true.also { WidgetChannelPinnedMessages.show(requireContext(), channel.id) }
                else -> false
            }
        }
        val headerSurface = if (channel.isPrivate()) Surface.DM else Surface.CHANNEL
        val customHeader = FeatureFlags.headerCustomizationsEnabled()
        actionBarTitleLayout?.setIdentityVisibility(
            showIcon = !customHeader || HeaderCustomization.identityVisible(headerSurface, Identity.AVATAR),
            showTitle = !customHeader || HeaderCustomization.identityVisible(headerSurface, Identity.TITLE),
        )
        val presence = channel.getDMRecipient()?.id?.let { StoreStream.getPresences().getPresences()[it] }
        actionBarTitleLayout?.setPresence(
            presence,
            WidgetHomeHeaderManager.headerStatusVisible(
                featureEnabled = FeatureFlags.chatHeaderStatusIndicatorsEnabled(),
                customizationsEnabled = customHeader,
                profileVisible = !customHeader ||
                    HeaderCustomization.identityVisible(headerSurface, Identity.AVATAR),
                statusVisible = HeaderCustomization.identityVisible(headerSurface, Identity.STATUS),
                channelStatusVisible = channel.isDMStatusVisible(presence),
            ),
        )
        toolbar.visibility = if (
            !customHeader || HeaderCustomization.identityVisible(headerSurface, Identity.HEADER)
        ) View.VISIBLE else View.GONE
        updateUnreadCount()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        // Leaving search changes whether the chat's toolbar leads back to it.
        refreshChatToolbar()
        AccessibilityDetectionNavigator.enqueueNoticeWhenEnabled(this)
        viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetHome::class.java, onNext = ::handleEvent)
        StoreStream.getUsers().observeMe()
            .filter { it.hasUnreadUrgentMessages }
            .ui(this)
            .appSubscribe(WidgetHome::class.java) { showUrgentMessageDialog() }
        ModalComponent.autoOpen(this)
        StoreStream.getNux().getNuxState()
            .filter { it.newUserType != null }
            .take(1)
            .ui(this)
            .appSubscribe(WidgetHome::class.java) { WidgetNewUserOnboarding.launch(requireContext()) }
        Observable.combineLatest(
            StoreStream.getGuildSelected().observeSelectedGuildId(),
            StoreStream.getGuilds().observeGuilds(),
            StoreStream.getGuilds().observeComputed(),
        ) { guildId, _, _ -> guildId }
            .ui(this)
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetHome::class.java, onNext = ::maybeLaunchGuildOnboarding)
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
    }

    private fun updateUnreadCount() {
        chatBinding?.let { chat ->
            chat.unread.homeToolbarUnreadCount.apply {
                text = unreadCount.takeIf { it > 0 }?.toString()
                visibility = if (unreadCount > 0) View.VISIBLE else View.GONE
                setOnClickListener { WidgetQuickMentionsDialog.show(parentFragmentManager) }
                chat.homeToolbar.positionUnreadCountView(this)
            }
        }
        (childFragmentManager.findFragmentByTag(FORUM_TAG) as? WidgetForumBrowser)
            ?.updateHomeUnreadCount(unreadCount)
    }

    private fun privateCallLauncher() = PrivateCallLauncher(
        appPermissionsRequests = this,
        appComponent = this,
        context = requireContext(),
        fragmentManager = childFragmentManager,
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

    private fun returnToMessageRequests() {
        StoreStream.selectChannel(0L, 0L)
        WidgetMessageRequests.show(requireContext())
    }

    private fun maybeLaunchGuildOnboarding(guildId: Long) {
        if (onboardingVisitGuildId != guildId) {
            onboardingVisitGuildId = guildId
            onboardingGuildId = null
        }
        if (guildId <= 0) return
        if (onboardingGuildId == guildId) return
        val guild = StoreStream.getGuilds().getGuild(guildId) ?: return
        if (!guild.hasFeature(GuildFeature.GUILD_ONBOARDING)) return
        val member = StoreStream.getGuilds().getMember(guildId, StoreStream.getUsers().getMe().id) ?: return
        if (StoreStream.getGuildOnboarding().get(guildId).completed) return
        if (!GuildMemberFlags.has(member.flags, GuildMemberFlags.STARTED_ONBOARDING)) return
        if (GuildMemberFlags.has(member.flags, GuildMemberFlags.COMPLETED_ONBOARDING)) return
        onboardingGuildId = guildId
        WidgetGuildOnboarding.launch(requireContext(), guildId)
    }

    companion object {
        internal enum class CenterSurface { NONE, CHAT, FORUM, DIRECTORY }

        private fun StoreChannelsSelected.ResolvedSelectedChannel.isResolved() =
            this !is StoreChannelsSelected.ResolvedSelectedChannel.Uninitialized

        internal fun centerSurface(selected: StoreChannelsSelected.ResolvedSelectedChannel): CenterSurface = when {
            selected is StoreChannelsSelected.ResolvedSelectedChannel.Channel &&
                selected.channel.type == Channel.DIRECTORY -> CenterSurface.DIRECTORY
            selected is StoreChannelsSelected.ResolvedSelectedChannel.Channel &&
                selected.channel.isThreadOnlyChannel() -> CenterSurface.FORUM
            selected.channelOrParent != null -> CenterSurface.CHAT
            else -> CenterSurface.NONE
        }

        private const val START_PANEL_PREWARM_DELAY_MS = 2_000L
        private const val GUILDS_TAG = "WidgetGuildsList"
        private const val CHANNELS_TAG = "WidgetChannelsList"
        private const val FORUM_TAG = "WidgetForumBrowser"
        private const val DIRECTORY_TAG = "WidgetDirectoryChannel"
        private const val MEMBERS_TAG = "WidgetChannelMembersList"
        private const val TOPIC_TAG = "WidgetChannelTopic"
        private const val ACTIONS_TAG = "WidgetChannelSidebarActions"
    }
}
