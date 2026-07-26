package com.discord.widgets.channels

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.stores.StoreStream
import com.facebook.drawee.view.SimpleDraweeView
import rx.Subscription

class WidgetChannelTopic : AppFragment(R.layout.widget_channel_topic) {
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
        val name = channel.name ?: channel.recipients.orEmpty().joinToString(", ") { it.username }
        requireView().findViewById<TextView>(R.id.channel_topic_name).text = name
        requireView().findViewById<TextView>(R.id.channel_topic_title).apply {
            text = channel.topic.orEmpty()
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        requireView().findViewById<View>(R.id.channel_aka).visibility = View.GONE
        requireView().findViewById<View>(R.id.channel_topic_more_icon).visibility = View.GONE
        requireView().findViewById<SimpleDraweeView>(R.id.channel_topic_channel_icon).setImageURI(
            channel.icon?.let { "https://cdn.discordapp.com/channel-icons/${channel.id}/$it.webp?size=64" },
        )
        requireView().contentDescription = listOf(name, channel.topic).filterNotNull().joinToString(", ")
    }
}
