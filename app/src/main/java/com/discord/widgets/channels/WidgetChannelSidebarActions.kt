package com.discord.widgets.channels

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.stores.StoreStream
import com.discord.views.channelsidebar.GuildChannelSideBarActionsView
import com.discord.views.channelsidebar.PrivateChannelSideBarActionsView
import rx.Subscription

class WidgetChannelSidebarActions : AppFragment(R.layout.widget_channel_sidebar_actions) {
    private var subscription: Subscription? = null

    override fun onStart() {
        super.onStart()
        subscription = StoreStream.getChannelsSelected().observeSelectedChannel().subscribe { channel ->
            view?.post { if (view != null) bind(channel) }
        }
    }

    override fun onStop() { subscription?.unsubscribe(); subscription = null; super.onStop() }

    private fun bind(channel: Channel?) {
        requireView().visibility = if (channel == null) View.GONE else View.VISIBLE
        if (channel == null) return
        val privateChannel = channel.type == Channel.DM || channel.type == Channel.GROUP_DM
        requireView().findViewById<PrivateChannelSideBarActionsView>(R.id.widget_channel_sidebar_actions_private_view).apply {
            visibility = if (privateChannel) View.VISIBLE else View.GONE
            if (privateChannel) configure(
                onCallClicked = View.OnClickListener { onCall?.invoke(channel.id, false) },
                onVideoClicked = View.OnClickListener { onCall?.invoke(channel.id, true) },
                onNotificationsClicked = View.OnClickListener { onNotifications?.invoke(channel.id) },
                onSearchClicked = View.OnClickListener { onSearch?.invoke(channel) },
            )
        }
        requireView().findViewById<GuildChannelSideBarActionsView>(R.id.widget_channel_sidebar_actions_guild_view).apply {
            visibility = if (privateChannel) View.GONE else View.VISIBLE
            if (!privateChannel) configure(
                onSearchClicked = { onSearch?.invoke(channel) },
                onNotificationsClicked = { onNotifications?.invoke(channel.id) },
                onPinsClicked = { onPins?.invoke(channel.id) },
                onSettingsClicked = { onSettings?.invoke(channel.id) },
                disablePins = false,
            )
        }
    }

    companion object {
        /**
         * Takes the whole channel, not its id: a guild channel searches its *guild*, while a DM
         * searches the channel itself, and the host cannot tell them apart from an id alone.
         */
        @JvmField var onSearch: ((Channel) -> Unit)? = null
        @JvmField var onNotifications: ((Long) -> Unit)? = null
        @JvmField var onPins: ((Long) -> Unit)? = null
        @JvmField var onSettings: ((Long) -> Unit)? = null
        @JvmField var onCall: ((Long, Boolean) -> Unit)? = null
    }
}
