package com.discord.widgets.chat.list.adapter

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.annotation.LayoutRes
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.models.message.Message
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.views.ThemedColor
import com.discord.widgets.chat.list.entries.ChatListEntry

/**
 * Base view holder for every chat row.
 *
 * It supplies the two things every row shares: the tinted background used while a blocked-message
 * chunk is expanded, and a tap detector that reports "the list was clicked" so the chat overlay can
 * dismiss itself even when the tap lands on a row rather than the gaps between rows.
 */
open class WidgetChatListItem(
    itemView: View,
    adapter: WidgetChatListAdapter,
) : MGRecyclerViewHolder<WidgetChatListAdapter, ChatListEntry>(itemView, adapter) {

    constructor(@LayoutRes layout: Int, adapter: WidgetChatListAdapter) : this(
        android.view.LayoutInflater.from(adapter.recycler.context)
            .inflate(layout, adapter.recycler, false),
        adapter,
    )

    private val defaultBackground: Drawable? = itemView.background
    private val backgroundBlockedExpanded: Drawable =
        ColorDrawable(ThemedColor.resolve(itemView.context, R.attr.theme_chat_blocked_bg))

    init {
        val tapGestureDetector = GestureDetector(
            itemView.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    adapter.eventHandler.onListClicked()
                    return super.onSingleTapUp(e)
                }
            },
        )
        itemView.setOnTouchListener { _, event -> tapGestureDetector.onTouchEvent(event) }
    }

    /**
     * Paints the left gutter and row wash that mark a message out.
     *
     * Three states, in priority order: an ephemeral, failed, or currently replied-to message is washed
     * with the neutral hover colour and a brand-coloured gutter; a message mentioning the viewer gets
     * the mention wash and a yellow gutter; anything else hides both views. The check runs on every bind
     * because a recycled row would otherwise keep the previous message's highlight.
     */
    fun configureCellHighlight(
        message: Message,
        highlightView: View,
        gutterView: View,
        isPendingReply: Boolean = false,
    ) {
        val isNeutralHighlight = message.isEphemeralMessage() || message.isFailed() || isPendingReply
        val isMentioned = adapter.isUserMentioned(message)

        val highlighted = isNeutralHighlight || isMentioned
        highlightView.visibility = if (highlighted) View.VISIBLE else View.GONE
        gutterView.visibility = if (highlighted) View.VISIBLE else View.GONE
        if (!highlighted) return

        val backgroundColor: Int
        val gutterColor: Int
        if (isNeutralHighlight) {
            backgroundColor = ThemedColor.resolve(
                highlightView.context,
                R.attr.colorBackgroundModifierHover,
            )
            gutterColor = ThemedColor.resolve(gutterView.context, R.attr.color_brand)
        } else {
            backgroundColor = ThemedColor.resolve(highlightView.context, R.attr.theme_chat_mentioned_me)
            gutterColor = ContextCompat.getColor(gutterView.context, R.color.status_yellow_500)
        }
        highlightView.setBackgroundColor(backgroundColor)
        gutterView.setBackgroundColor(gutterColor)
    }

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        itemView.background =
            if (data.isInExpandedBlockedMessageChunk()) backgroundBlockedExpanded else defaultBackground
    }
}
