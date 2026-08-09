package com.discord.widgets.chat.list.adapter

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.annotation.LayoutRes
import androidx.cardview.widget.CardView
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

    /**
     * Rows declaring their own highlight view keep drawing it; the rest are washed through the row
     * background, which is the only thing that covers a root carrying padding or one whose children
     * cannot be stacked. A card fills its row and owns its background, so it has nowhere to wash.
     */
    private val ownsHighlightView: Boolean =
        itemView.findViewById<View>(R.id.chat_list_adapter_item_highlighted_bg) != null ||
            itemView is CardView

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
     * The check runs on every bind because a recycled row would otherwise keep the previous
     * message's highlight.
     */
    fun configureCellHighlight(
        message: Message,
        highlightView: View,
        gutterView: View,
        isPendingReply: Boolean = false,
    ) {
        val colors = highlightColors(message, isPendingReply)
        highlightView.visibility = if (colors != null) View.VISIBLE else View.GONE
        gutterView.visibility = if (colors != null) View.VISIBLE else View.GONE
        if (colors == null) return
        highlightView.setBackgroundColor(colors.first)
        gutterView.setBackgroundColor(colors.second)
    }

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        itemView.background = when {
            data.isInExpandedBlockedMessageChunk() -> backgroundBlockedExpanded
            else -> rowHighlight(data) ?: defaultBackground
        }
    }

    /**
     * Wash and gutter colours, or null when the row is not marked out. An ephemeral, failed or
     * currently replied-to message takes the neutral hover colour with a brand gutter; one
     * mentioning the viewer takes the mention wash with a yellow gutter.
     */
    private fun highlightColors(message: Message, isPendingReply: Boolean): Pair<Int, Int>? {
        val context = itemView.context
        return when {
            message.isEphemeralMessage() || message.isFailed() || isPendingReply -> Pair(
                ThemedColor.resolve(context, R.attr.colorBackgroundModifierHover),
                ThemedColor.resolve(context, R.attr.color_brand),
            )
            adapter.isUserMentioned(message) -> Pair(
                ThemedColor.resolve(context, R.attr.theme_chat_mentioned_me),
                ContextCompat.getColor(context, R.color.status_yellow_500),
            )
            else -> null
        }
    }

    /** The wash as a row background, for rows with nowhere to put a highlight view. */
    private fun rowHighlight(data: ChatListEntry): Drawable? {
        if (ownsHighlightView) return null
        val message = data.highlightMessage ?: return null
        val (wash, gutter) = highlightColors(message, data.isPendingReply) ?: return null
        val drawable = CellHighlightDrawable(
            wash = wash,
            gutter = gutter,
            gutterWidth = GUTTER_WIDTH_DP * itemView.resources.displayMetrics.density,
        )
        return defaultBackground?.let { LayerDrawable(arrayOf(drawable, it)) } ?: drawable
    }

    /** The row wash and the gutter that marks its leading edge. */
    private class CellHighlightDrawable(
        private val wash: Int,
        private val gutter: Int,
        private val gutterWidth: Float,
    ) : Drawable() {

        private val paint = Paint()

        override fun draw(canvas: Canvas) {
            paint.color = wash
            canvas.drawRect(bounds, paint)
            paint.color = gutter
            canvas.drawRect(
                bounds.left.toFloat(),
                bounds.top.toFloat(),
                bounds.left + gutterWidth,
                bounds.bottom.toFloat(),
                paint,
            )
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val GUTTER_WIDTH_DP = 2f
    }
}
