package com.discord.widgets.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.panels.OverlappingPanelsLayout
import com.discord.panels.PanelState
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import rx.Subscription

fun interface BackHandler {
    fun onBackPressed(): Boolean
}

class WidgetHome : Fragment(R.layout.widget_home), BackHandler {
    private lateinit var panels: HomePanelsLayout
    private var navigationSubscription: Subscription? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        panels = view.findViewById(R.id.overlapping_panels)
        panels.registerStartPanelStateListeners(
            OverlappingPanelsLayout.PanelStateListener { StoreStream.getNavigation().setLeftPanelState(it) },
        )
        panels.registerEndPanelStateListeners(
            OverlappingPanelsLayout.PanelStateListener { StoreStream.getNavigation().setRightPanelState(it) },
        )
    }

    override fun onStart() {
        super.onStart()
        val navigation = StoreStream.getNavigation()
        navigationSubscription = navigation.getNavigationPanelAction().subscribe { action ->
            activity?.runOnUiThread { navigation.setNavigationPanelAction(action, panels) }
        }
    }

    override fun onStop() {
        navigationSubscription?.unsubscribe()
        navigationSubscription = null
        super.onStop()
    }

    override fun onBackPressed(): Boolean = when (panels.getSelectedPanel()) {
        OverlappingPanelsLayout.Panel.CENTER -> false
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
}
