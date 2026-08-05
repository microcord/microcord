package com.discord.widgets.chat.polls

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.stores.StoreStream

class PollCreateLauncherFragment : Fragment(R.layout.fragment_poll_launcher) {
    private var launched = false

    override fun onResume() {
        super.onResume()
        if (launched) return
        launched = true
        val channel = StoreStream.getChannelsSelected().getSelectedChannel()
        if (channel == null) {
            (parentFragment as? DialogFragment)?.dismiss()
            return
        }
        (parentFragment as? DialogFragment)?.dismiss()
        WidgetPollCreate.launch(requireContext(), channel.id, channel.getDisplayNameOrDefault(requireContext()))
    }
}
