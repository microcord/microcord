package com.discord.widgets.home

import android.os.Bundle
import android.view.View
import android.view.ViewStub
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import androidx.fragment.app.commit
import androidx.appcompat.widget.Toolbar
import com.discord.R
import com.discord.panels.OverlappingPanelsLayout
import com.discord.panels.PanelState
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.widgets.channels.list.WidgetChannelsList
import com.discord.widgets.channels.memberlist.WidgetChannelMembersList
import com.discord.widgets.guilds.list.WidgetGuildsList
import com.discord.widgets.chat.pins.WidgetChannelPinnedMessages
import com.discord.models.message.Message
import com.discord.api.channel.Channel
import rx.Subscription

fun interface BackHandler {
    fun onBackPressed(): Boolean
}

class WidgetHome : Fragment(R.layout.widget_home), BackHandler, WidgetChannelPinnedMessages.Host {
    private lateinit var panels: HomePanelsLayout
    private var navigationSubscription: Subscription? = null
    private var selectedChannelSubscription: Subscription? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        panels = view.findViewById(R.id.overlapping_panels)
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

    override fun onBackPressed(): Boolean {
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack()
            return true
        }
        if (panels.getSelectedPanel() == OverlappingPanelsLayout.Panel.CENTER) {
            panels.openStartPanel()
        } else {
            panels.closePanels()
        }
        return true
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
                    childFragmentManager.commit {
                        replace(R.id.panel_center, WidgetChannelPinnedMessages.newInstance(channel.id))
                        addToBackStack("pins")
                    }
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
    }
}
