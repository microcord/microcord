package com.discord.widgets.chat.managereactions

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.views.BurstPalette
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

    /** A tab: [reaction]'s normal reactions, or with [burst] its super reactions, [count] of them. */
    data class ReactionEmojiItem(
        val reaction: MessageReaction,
        val burst: Boolean,
        val count: Int,
        val isSelected: Boolean,
    ) : MGRecyclerDataPayload {
        override val key = keyOf(reaction.emoji, burst)
        override val type = TYPE_REACTION_EMOJI_ITEM

        companion object {
            fun keyOf(emoji: MessageReactionEmoji, burst: Boolean): String =
                if (burst) "${emoji.reactionKey}#burst" else emoji.reactionKey
        }
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
            counter.text = data.count.toString()
            // A super reaction's tab is tinted as its pill is in the chat, so it reads as that one.
            val palette = if (data.burst) {
                BurstPalette.of(data.reaction.burstColors, StoreStream.getUserSettingsSystem().isDarkTheme())
            } else {
                null
            }
            container.background = if (data.burst) burstBackground(palette) else plainBackground
            counter.setTextColor(palette?.let { ColorStateList.valueOf(it.accent) } ?: plainCounterColor)
            selected.visibility = if (data.isSelected) View.VISIBLE else View.INVISIBLE
            container.setOnClickListener { adapter.onEmojiSelectedListener?.invoke(data.key) }
        }

        private val plainBackground = container.background
        private val plainCounterColor = counter.textColors

        /** A pill about the chat chip's height, centred in the taller tab rather than filling it. */
        private fun burstBackground(palette: BurstPalette?): Drawable {
            val pill = GradientDrawable().apply {
                val color = palette?.background ?: ColorCompat.getThemedColor(container.context, R.attr.color_brand_500)
                val opacity = palette?.opacity ?: BurstPalette.DARK_OPACITY
                setColor(ColorUtils.setAlphaComponent(color, (opacity * 255).toInt()))
                cornerRadius = dpToPixels(8).toFloat()
            }
            val inset = dpToPixels(9)
            return InsetDrawable(pill, 0, inset, 0, inset)
        }

        private companion object {
            val EMOJI_SIZE_PX = dpToPixels(20)
        }
    }

    private companion object {
        const val TYPE_REACTION_EMOJI_ITEM = 0
    }
}
