package com.discord.widgets.home

import android.os.Bundle
import android.view.View
import android.view.ViewStub
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import androidx.fragment.app.commitNow
import androidx.fragment.app.commit
import androidx.appcompat.widget.Toolbar
import com.discord.R
import com.discord.panels.OverlappingPanelsLayout
import com.discord.panels.PanelState
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.widgets.channels.list.WidgetChannelsList
import com.discord.widgets.channels.memberlist.WidgetChannelMembersList
import com.discord.widgets.channels.WidgetChannelTopic
import com.discord.widgets.channels.WidgetChannelSidebarActions
import com.discord.widgets.guilds.list.WidgetGuildsList
import com.discord.widgets.chat.pins.WidgetChannelPinnedMessages
import com.discord.widgets.search.WidgetSearch
import com.discord.models.message.Message
import com.discord.api.channel.Channel
import com.discord.api.channel.isPrivate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rx.Subscription

class WidgetHome : AppFragment(R.layout.widget_home), WidgetChannelPinnedMessages.Host {
    private lateinit var panels: HomePanelsLayout
    private var navigationSubscription: Subscription? = null
    private var selectedChannelSubscription: Subscription? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        panels = view.findViewById(R.id.overlapping_panels)
        // Registered here rather than in onViewBound so `panels` is already resolved, and before the
        // child transactions below so this stays behind WidgetTabsHost in the registry.
        setOnBackPressed(::handleBackPressed)
        panels.registerStartPanelStateListeners(
            OverlappingPanelsLayout.PanelStateListener { StoreStream.getNavigation().setLeftPanelState(it) },
        )
        panels.registerEndPanelStateListeners(
            OverlappingPanelsLayout.PanelStateListener { StoreStream.getNavigation().setRightPanelState(it) },
        )
        if (childFragmentManager.findFragmentByTag(GUILDS_TAG) == null) {
            childFragmentManager.commitNow { replace(R.id.widget_guilds, WidgetGuildsList(), GUILDS_TAG) }
        }
        if (childFragmentManager.findFragmentByTag(CHANNELS_TAG) == null) {
            childFragmentManager.commitNow { replace(R.id.widget_channels, WidgetChannelsList(), CHANNELS_TAG) }
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
        WidgetChannelSidebarActions.onPins = ::openPins
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
        selectedChannelSubscription = StoreStream.getChannelsSelected().observeSelectedChannel().subscribe { channel ->
            activity?.runOnUiThread {
                lockCloseRightPanel(channel == null)
                panels.setStartPanelUseFullPortraitWidth(channel == null)
                panels.setStartPanelLockState(
                    if (channel == null) OverlappingPanelsLayout.LockState.OPEN
                    else OverlappingPanelsLayout.LockState.UNLOCKED,
                )
                if (channel != null) ensureChatInflated()
                view?.findViewById<View>(R.id.widget_home_panel_center_chat)?.visibility =
                    if (channel == null) View.GONE else View.VISIBLE
                if (channel != null) configureToolbar(channel)
            }
        }
    }

    override fun onStop() {
        navigationSubscription?.unsubscribe()
        navigationSubscription = null
        selectedChannelSubscription?.unsubscribe()
        selectedChannelSubscription = null
        super.onStop()
    }

    override fun onDestroyView() {
        WidgetChannelSidebarActions.onPins = null
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

    /**
     * Back from the chat panel. A peeked channel steps back to the channel it was peeked from;
     * anything else opens the left panel.
     *
     * Two reference branches are not reachable yet and are deliberately absent rather than
     * approximated: dismissing an open thread-creation composer (`StoreChannelsSelected`'s
     * `dismissCreateThread` is `@StoreThread` and has no UI-callable wrapper), and re-entering the
     * fullscreen call surface when the selected channel is the vocal channel currently connected
     * (`WidgetCallFullscreen` / `WidgetCallPreviewFullscreen` do not exist in this project).
     */
    internal fun handleCenterPanelBack() {
        val selected = StoreStream.getChannelsSelected().getResolvedSelectedChannel()
            as? StoreChannelsSelected.ResolvedSelectedChannel.Channel
        val peekParent = selected?.peekParent
        if (selected != null && peekParent != null) {
            StoreStream.selectChannel(selected.channel.guildId, peekParent)
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
        toolbar.title = channel.name ?: channel.recipients.orEmpty().joinToString(", ") { it.username }
        if (toolbar.menu.size() == 0) {
            toolbar.inflateMenu(R.menu.menu_chat_toolbar)
            toolbar.menu.findItem(R.id.menu_chat_side_panel)?.isVisible = true
            toolbar.menu.add(0, R.id.menu_private_channel_sidebar_pinned_messages, 1, R.string.pinned_messages)
                .setIcon(R.drawable.ic_pin_24dp)
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_chat_side_panel -> true.also { panels.openEndPanel() }
                R.id.menu_private_channel_sidebar_pinned_messages -> true.also {
                    openPins(channel.id)
                }
                else -> false
            }
        }
    }

    override fun pinnedMessagesStore() = StoreStream.getPinnedMessages()
    override fun channelForPins(channelId: Long) = StoreStream.getChannels().getChannel(channelId)
    override fun jumpToPinnedMessage(channelId: Long, messageId: Long) {
        childFragmentManager.popBackStack()
        StoreStream.getMessagesLoader().jumpToMessage(channelId, messageId)
    }
    override fun onPinnedMessageLongPress(message: Message) = false

    private fun openPins(channelId: Long) {
        childFragmentManager.commit {
            replace(R.id.panel_center, WidgetChannelPinnedMessages.newInstance(channelId))
            addToBackStack("pins")
        }
    }

    private fun openMuteSettings(channelId: Long) {
        val channel = StoreStream.getChannels().getChannel(channelId) ?: return
        val settings = StoreStream.getUserGuildSettings()
        val muted = settings.getGuildSettings()[channel.guildId]
            ?.getChannelOverride(channelId)?.isMuted == true
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.notification_settings)
            .setItems(arrayOf(getString(if (muted) R.string.unmute else R.string.mute))) { _, _ ->
                settings.setChannelMuted(requireContext(), channelId, !muted)
            }
            .show()
    }

    private fun <T : View> View.findDescendant(type: Class<T>): T? {
        if (type.isInstance(this)) return type.cast(this)
        if (this !is android.view.ViewGroup) return null
        for (index in 0 until childCount) getChildAt(index).findDescendant(type)?.let { return it }
        return null
    }

    private companion object {
        const val GUILDS_TAG = "WidgetGuildsList"
        const val CHANNELS_TAG = "WidgetChannelsList"
        const val MEMBERS_TAG = "WidgetChannelMembersList"
        const val TOPIC_TAG = "WidgetChannelTopic"
        const val ACTIONS_TAG = "WidgetChannelSidebarActions"
    }
}
