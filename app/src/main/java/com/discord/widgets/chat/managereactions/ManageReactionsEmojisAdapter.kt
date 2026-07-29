package com.discord.widgets.chat.managereactions

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.message.reaction.MessageReaction
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.renderReactionEmoji

class ManageReactionsEmojisAdapter(recycler: RecyclerView) :
    MGRecyclerAdapterSimple<ManageReactionsEmojisAdapter.ReactionEmojiItem>(
        recycler,
        deferredDiffs = false,
    ) {

    var onEmojiSelectedListener: ((String) -> Unit)? = null

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, ReactionEmojiItem> = when (viewType) {
        TYPE_REACTION_EMOJI_ITEM -> ReactionEmojiViewHolder(this)
        else -> throw invalidViewTypeException(viewType)
    }

    data class ReactionEmojiItem(
        val reaction: MessageReaction,
        val isSelected: Boolean,
    ) : MGRecyclerDataPayload {
        override val key = reaction.emoji.reactionKey
        override val type = TYPE_REACTION_EMOJI_ITEM
    }

    private class ReactionEmojiViewHolder(adapter: ManageReactionsEmojisAdapter) :
        MGRecyclerViewHolder<ManageReactionsEmojisAdapter, ReactionEmojiItem>(
            R.layout.widget_manage_reactions_emoji,
            adapter,
        ) {
        private val container = itemView.findViewById<View>(R.id.manage_reactions_emoji_container)
        private val counter = itemView.findViewById<TextView>(R.id.manage_reactions_emoji_counter)
        private val emoji = itemView.findViewById<SimpleDraweeSpanTextView>(
            R.id.manage_reactions_emoji_emoji_textview,
        )
        private val selected = itemView.findViewById<View>(R.id.manage_reactions_emoji_selected_indicator)

        override fun onConfigure(position: Int, data: ReactionEmojiItem) {
            super.onConfigure(position, data)
            renderReactionEmoji(emoji, data.reaction.emoji, animate = true, sizePx = EMOJI_SIZE_PX)
            counter.text = data.reaction.count.toString()
            selected.visibility = if (data.isSelected) View.VISIBLE else View.INVISIBLE
            container.setOnClickListener { adapter.onEmojiSelectedListener?.invoke(data.key) }
        }

        private companion object {
            val EMOJI_SIZE_PX = dpToPixels(20)
        }
    }

    private companion object {
        const val TYPE_REACTION_EMOJI_ITEM = 0
    }
}
