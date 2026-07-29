package com.discord.widgets.guilds.join

import android.view.View
import androidx.recyclerview.widget.RecyclerView

open class BaseChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    open fun bind(data: ChannelItem) = Unit
}
