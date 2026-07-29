package com.discord.widgets.chat.list

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter

/**
 * Draws the vertical line that connects a message which started a thread to that thread's preview
 * card, across whatever rows (attachments, embeds, reactions) sit between them.
 *
 * The spine is drawn *over* the rows rather than as an item offset, so it crosses row boundaries
 * without every affected row having to reserve space for it. Which rows it covers is decided when
 * the list is built and carried on [com.discord.widgets.chat.list.entries.ChatListEntry.shouldShowThreadSpine].
 */
class ThreadSpineItemDecoration(
    val context: Context,
    val adapter: WidgetChatListAdapter,
) : RecyclerView.ItemDecoration() {

    private val drawable = ContextCompat.getDrawable(context, R.drawable.thread_embed_spine_drawable)
    private val startPositionPx =
        context.resources.getDimensionPixelSize(R.dimen.chat_list_spine_margin)
    private val bounds = Rect()

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (parent.layoutManager == null) return
        canvas.save()
        repeat(parent.childCount) { index ->
            val child = parent.getChildAt(index) ?: return@repeat
            val position = parent.getChildAdapterPosition(child)
            if (position < 0 || position >= adapter.itemCount) return@repeat
            if (!adapter.getItem(position).shouldShowThreadSpine) return@repeat
            parent.getDecoratedBoundsWithMargins(child, bounds)
            drawable?.let {
                it.setBounds(
                    startPositionPx,
                    bounds.top,
                    startPositionPx + it.intrinsicWidth,
                    bounds.bottom,
                )
                it.draw(canvas)
            }
        }
        canvas.restore()
    }
}
