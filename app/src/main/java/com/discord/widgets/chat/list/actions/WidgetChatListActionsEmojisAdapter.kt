package com.discord.widgets.chat.list.actions

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.models.domain.emoji.Emoji
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

class WidgetChatListActionsEmojisAdapter(recycler: RecyclerView) :
    MGRecyclerAdapterSimple<EmojiItem>(recycler, deferredDiffs = false) {
    var onClickEmoji: (Emoji) -> Unit = {}
    var onClickMoreEmojis: () -> Unit = {}

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, EmojiItem> = when (viewType) {
        EmojiItem.TYPE_EMOJI -> EmojiViewHolder(this)
        EmojiItem.TYPE_MORE_EMOJIS -> MoreEmojisViewHolder(this)
        else -> throw invalidViewTypeException(viewType)
    }

    fun setData(emojis: List<Emoji>, emojisToShow: Int) =
        setData(EmojiItem.from(emojis, emojisToShow))
}
