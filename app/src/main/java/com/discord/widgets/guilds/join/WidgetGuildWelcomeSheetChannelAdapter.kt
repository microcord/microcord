package com.discord.widgets.guilds.join

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R

class WidgetGuildWelcomeSheetChannelAdapter : RecyclerView.Adapter<BaseChannelViewHolder>() {
    private var data: List<ChannelItem> = emptyList()

    fun setData(data: List<ChannelItem>) {
        this.data = data
        notifyDataSetChanged()
    }

    override fun getItemCount() = data.size
    override fun getItemViewType(position: Int) = data[position].type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseChannelViewHolder {
        require(viewType == ChannelItem.TYPE_CHANNEL) { "invalid view type: $viewType" }
        return ChannelViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.widget_guild_welcome_channel, parent, false),
        )
    }

    override fun onBindViewHolder(holder: BaseChannelViewHolder, position: Int) = holder.bind(data[position])
}
