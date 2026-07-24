package com.discord.widgets.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import com.discord.R
import com.discord.panels.OverlappingPanelsLayout
import com.discord.panels.PanelState
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.widgets.channels.list.WidgetChannelsList
import com.discord.widgets.channels.memberlist.WidgetChannelMembersList
import com.discord.widgets.guilds.list.WidgetGuildsList
import rx.Subscription

fun interface BackHandler {
    fun onBackPressed(): Boolean
}

class WidgetHome : Fragment(R.layout.widget_home), BackHandler {
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
            activity?.runOnUiThread { lockCloseRightPanel(channel == null) }
        }
    }

    override fun onStop() {
        navigationSubscription?.unsubscribe()
        navigationSubscription = null
        selectedChannelSubscription?.unsubscribe()
        selectedChannelSubscription = null
        super.onStop()
    }

    override fun onBackPressed(): Boolean = when (panels.getSelectedPanel()) {
        OverlappingPanelsLayout.Panel.CENTER -> {
            panels.openStartPanel()
            true
        }
        else -> {
            panels.closePanels()
            true
        }
    }

    fun getPanelLayout(): PanelLayout = panels

    fun lockCloseRightPanel(locked: Boolean) {
        panels.setEndPanelLockState(
            if (locked) OverlappingPanelsLayout.LockState.CLOSE else OverlappingPanelsLayout.LockState.UNLOCKED,
        )
        if (locked) StoreStream.getNavigation().setRightPanelState(PanelState.Closed)
    }

    private companion object {
        const val GUILDS_TAG = "WidgetGuildsList"
        const val CHANNELS_TAG = "WidgetChannelsList"
        const val MEMBERS_TAG = "WidgetChannelMembersList"
    }
}
