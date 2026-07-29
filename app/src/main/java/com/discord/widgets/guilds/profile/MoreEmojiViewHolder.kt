package com.discord.widgets.guilds.profile

import android.widget.TextView
import com.discord.R

class MoreEmojiViewHolder(private val text: TextView) : BaseEmojiViewHolder(text) {
    override fun bind(data: EmojiItem) {
        text.text = text.resources.getString(
            R.string.extra_emoji_count,
            (data as EmojiItem.MoreEmoji).extraEmojiCount.toString(),
        )
    }
}
