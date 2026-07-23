package com.discord.widgets.guilds.create

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel

class GuildTemplateChannelsView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private val adapter = ChannelsAdapter()

    init {
        LayoutInflater.from(context).inflate(R.layout.guild_template_channels_view, this, true)
        requireNotNull<RecyclerView>(findViewById(R.id.guild_template_channels_recycler_view)).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@GuildTemplateChannelsView.adapter
            setHasFixedSize(false)
        }
    }

    fun updateView(channels: List<Channel>) {
        adapter.channels = channels
    }

    private class ChannelsAdapter : RecyclerView.Adapter<ChannelViewHolder>() {
        var channels: List<Channel> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ChannelViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.guild_template_channels_item_view, parent, false) as LinearLayout,
        )

        override fun getItemCount() = channels.size

        override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) = holder.bind(channels[position])
    }

    private class ChannelViewHolder(root: LinearLayout) : RecyclerView.ViewHolder(root) {
        private val icon = requireNotNull<ImageView>(root.findViewById(R.id.guild_template_channels_item_image))
        private val name = requireNotNull<TextView>(root.findViewById(R.id.guild_template_channels_item_name))
        private val defaultMargin = (icon.layoutParams as ViewGroup.MarginLayoutParams).marginStart

        fun bind(channel: Channel) {
            name.text = channel.name.orEmpty()
            icon.setImageResource(iconForChannelType(channel.type))
            (icon.layoutParams as ViewGroup.MarginLayoutParams).also {
                it.marginStart = if (channel.parentId > 0) (24 * icon.resources.displayMetrics.density).toInt() else defaultMargin
                icon.layoutParams = it
            }
        }
    }

    companion object {
        @DrawableRes
        internal fun iconForChannelType(type: Int) = when (type) {
            Channel.CATEGORY -> R.drawable.ic_chevron_down_grey_12dp
            Channel.GUILD_VOICE -> R.drawable.ic_channel_voice_16dp
            else -> R.drawable.ic_channel_text_16dp
        }
    }
}
