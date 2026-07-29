package com.discord.widgets.chat.list.reactions

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.renderReactionEmoji

class ManageReactionsEmojisAdapter(recycler: RecyclerView) :
    MGRecyclerAdapterSimple<ReactionEmojiItem>(recycler, deferredDiffs = false) {
    var onEmojiSelected: ((String) -> Unit)? = null

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, ReactionEmojiItem> =
        if (viewType == 0) ReactionEmojiViewHolder(this) else throw invalidViewTypeException(viewType)

    private class ReactionEmojiViewHolder(adapter: ManageReactionsEmojisAdapter) :
        MGRecyclerViewHolder<ManageReactionsEmojisAdapter, ReactionEmojiItem>(
            R.layout.widget_manage_reactions_emoji,
            adapter,
        ) {
        private val emoji = itemView.findViewById<SimpleDraweeSpanTextView>(
            R.id.manage_reactions_emoji_emoji_textview,
        )
        private val count = itemView.findViewById<TextView>(R.id.manage_reactions_emoji_counter)
        private val selected = itemView.findViewById<View>(R.id.manage_reactions_emoji_selected_indicator)

        override fun onConfigure(position: Int, data: ReactionEmojiItem) {
            renderReactionEmoji(emoji, data.reaction.emoji, animate = true)
            count.text = data.reaction.count.toString()
            selected.visibility = if (data.isSelected) View.VISIBLE else View.INVISIBLE
            itemView.setOnClickListener { adapter.onEmojiSelected?.invoke(data.key) }
        }
    }
}
