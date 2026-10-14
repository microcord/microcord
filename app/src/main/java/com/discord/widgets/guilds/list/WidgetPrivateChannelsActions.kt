package com.discord.widgets.guilds.list

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.app.AppBottomSheet
import com.discord.databinding.WidgetPrivateChannelsActionsBinding
import com.discord.stores.StoreChannels
import com.discord.stores.StoreStream

/**
 * Long-press sheet for the guild list's Direct Messages button. Microcord addition; 126.21 has no
 * long-press there.
 *
 * "Mark DMs as Read" marks every DM read and leaves servers alone.
 *
 * "Mark All as Read" marks everything read: every DM, and every server the way its own "Mark As
 * Read" does (channels, joined threads, scheduled events). It all goes through the one paced
 * `read-states/ack-bulk` queue, as desktop's folder "Mark As Read" does.
 */
class WidgetPrivateChannelsActions : AppBottomSheet() {
    override fun getContentViewResId() = R.layout.widget_private_channels_actions

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = WidgetPrivateChannelsActionsBinding.bind(view)
        ViewCompat.setAccessibilityHeading(binding.privateChannelsActionsHeader, true)
        binding.privateChannelsActionsMarkDmsRead.setOnClickAndDismissListener {
            StoreStream.getMessageAck().bulkAck(listOf(StoreChannels.PRIVATE_CHANNELS_ID)) { false }
        }
        binding.privateChannelsActionsMarkAllRead.setOnClickAndDismissListener {
            val ids = StoreStream.getGuilds().getGuilds().keys + StoreChannels.PRIVATE_CHANNELS_ID
            StoreStream.getMessageAck().bulkAck(ids) {
                StoreStream.getChannels().getChannel(it)?.isThreadOnlyChannel() == true
            }
        }
    }

    companion object {
        fun show(fragmentManager: FragmentManager) =
            WidgetPrivateChannelsActions().show(fragmentManager, WidgetPrivateChannelsActions::class.java.name)
    }
}
