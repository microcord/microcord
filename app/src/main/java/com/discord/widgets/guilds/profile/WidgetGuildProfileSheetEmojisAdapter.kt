package com.discord.widgets.guilds.profile

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.domain.emoji.Emoji
import com.facebook.drawee.view.SimpleDraweeView

class WidgetGuildProfileSheetEmojisAdapter : RecyclerView.Adapter<BaseEmojiViewHolder>() {
    private var data: List<EmojiItem> = emptyList()
    var onClickEmoji: () -> Unit = {}

    override fun getItemCount(): Int = data.size
    override fun getItemViewType(position: Int): Int = data[position].type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseEmojiViewHolder = when (viewType) {
        EmojiItem.TYPE_EMOJI -> EmojiViewHolder(
            LayoutInflater.from(parent.context).inflate(
                R.layout.widget_guild_profile_emoji_item,
                parent,
                false,
            ) as SimpleDraweeView,
        )
        EmojiItem.TYPE_MORE_EMOJI -> MoreEmojiViewHolder(
            LayoutInflater.from(parent.context).inflate(
                R.layout.widget_guild_profile_emoji_extra,
                parent,
                false,
            ) as TextView,
        )
        else -> throw IllegalArgumentException("invalid view type: $viewType")
    }

    override fun onBindViewHolder(holder: BaseEmojiViewHolder, position: Int) {
        holder.bind(data[position])
        holder.itemView.setOnClickListener { onClickEmoji() }
    }

    fun setData(emojis: List<Emoji>, maxEmojisToShow: Int) {
        data = getEmojiItems(emojis, maxEmojisToShow)
        notifyDataSetChanged()
    }

    companion object {
        internal fun getEmojiItems(emojis: List<Emoji>, maxEmojisToShow: Int): List<EmojiItem> {
            val shown = emojis.take(maxEmojisToShow).mapTo(ArrayList<EmojiItem>()) { EmojiItem.EmojiData(it) }
            var extra = emojis.size - shown.size
            if (extra > 0) {
                if (shown.size == maxEmojisToShow && shown.isNotEmpty()) {
                    shown.removeAt(shown.lastIndex)
                    extra++
                }
                shown += EmojiItem.MoreEmoji(extra.coerceAtMost(99))
            }
            return shown
        }
    }
}
