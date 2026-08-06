package com.discord.widgets.chat.polls

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.stores.StoreStream

/**
 * The attachment tray's poll tab, which is a button wearing a page: selecting it opens the poll
 * composer and closes the tray.
 *
 * Being resumed is not the same as being chosen. The tray's pager keeps the neighbouring page
 * resumed so it can be swiped to instantly, so acting on [onResume] alone fires this while the user
 * is looking at the tab next to it — [setUserVisibleHint] is what actually says "this page is the
 * one on screen".
 */
class PollCreateLauncherFragment : Fragment(R.layout.fragment_poll_launcher) {
    private var launched = false

    override fun setUserVisibleHint(isVisibleToUser: Boolean) {
        super.setUserVisibleHint(isVisibleToUser)
        if (isVisibleToUser) launchIfSelected()
    }

    override fun onResume() {
        super.onResume()
        launchIfSelected()
    }

    private fun launchIfSelected() {
        if (launched || !userVisibleHint || !isResumed) return
        launched = true
        val channel = StoreStream.getChannelsSelected().getSelectedChannel()
        (parentFragment as? DialogFragment)?.dismiss()
        if (channel == null) return
        WidgetPollCreate.launch(requireContext(), channel.id, channel.getDisplayNameOrDefault(requireContext()))
    }
}
