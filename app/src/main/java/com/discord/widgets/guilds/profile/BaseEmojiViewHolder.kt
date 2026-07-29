package com.discord.widgets.guilds.profile

import android.view.View
import androidx.recyclerview.widget.RecyclerView

open class BaseEmojiViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    open fun bind(data: EmojiItem) = Unit
}
