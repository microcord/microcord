package com.discord.widgets.chat.list

import android.content.Context
import android.graphics.Canvas
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.children
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isSystemDM
import com.discord.api.message.MessageTypes
import com.discord.api.permission.Permission
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.discord.stores.UserSettings
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.permissions.PermissionUtils
import com.discord.widgets.chat.list.adapter.TapTapBehavior
import kotlin.math.max
import kotlin.math.min

/**
 * Swipe a message left to reply to it, as on Discord's newer clients (Text & Images > "Swiping
 * Right to Left").
 *
 * A message spans several rows (text, attachments, embeds, stickers); they all follow the finger
 * together up to [MAX_DP] and spring back, with one reply arrow fading in beside them. Passing
 * [TRIGGER_DP] gives a haptic tick, and releasing past it replies. Nothing is ever swiped away: the
 * swipe threshold is out of reach.
 */
internal class SwipeToReply(
    context: Context,
    private val messageAt: (RecyclerView.ViewHolder) -> Message?,
    private val onReply: (Message) -> Unit,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {

    private val trigger = dpToPixels(TRIGGER_DP).toFloat()
    private val maxOffset = dpToPixels(MAX_DP).toFloat()
    private val iconMargin = dpToPixels(ICON_MARGIN_DP)
    private val icon = AppCompatResources.getDrawable(context, R.drawable.ic_reply_24dp)?.mutate()?.also {
        DrawableCompat.setTint(it, ColorCompat.getThemedColor(context, R.attr.colorInteractiveNormal))
    }
    private var armed: Message? = null

    override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
        if (replyable(viewHolder) != null) ItemTouchHelper.LEFT else 0

    override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = Float.MAX_VALUE

    override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE

    override fun onChildDraw(
        canvas: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        val offset = max(dX, -maxOffset)
        if (isCurrentlyActive) {
            val past = -offset >= trigger
            if (past && armed == null) viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            armed = if (past) replyable(viewHolder) else null
        }
        val rows = rowsOf(recyclerView, viewHolder)
        rows.forEach { it.translationX = offset }
        icon?.let { drawable ->
            val view = viewHolder.itemView
            val size = drawable.intrinsicWidth
            val right = view.right - iconMargin
            val top = (rows.minOf { it.top } + rows.maxOf { it.bottom } - size) / 2
            drawable.setBounds(right - size, top, right, top + size)
            drawable.alpha = (min(1f, -offset / trigger) * 255).toInt()
            drawable.draw(canvas)
        }
        super.onChildDraw(canvas, recyclerView, viewHolder, offset, dY, actionState, isCurrentlyActive)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        rowsOf(recyclerView, viewHolder).forEach { it.translationX = 0f }
        super.clearView(recyclerView, viewHolder)
        val message = armed ?: return
        armed = null
        onReply(message)
    }

    /** Every attached row of the swiped row's message, the swiped row included. */
    private fun rowsOf(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): List<View> {
        val id = messageAt(viewHolder)?.id ?: return listOf(viewHolder.itemView)
        return recyclerView.children.filter { child ->
            child === viewHolder.itemView || messageAt(recyclerView.getChildViewHolder(child))?.id == id
        }.toList()
    }

    private fun replyable(viewHolder: RecyclerView.ViewHolder): Message? =
        messageAt(viewHolder)?.takeIf(::canReply)

    companion object {
        private const val TRIGGER_DP = 64
        private const val MAX_DP = 96
        private const val ICON_MARGIN_DP = 24

        /** Off when swiping opens channel details instead, read live so it needs no restart; then the message actions' Reply rule. */
        fun canReply(message: Message): Boolean {
            if (!UserSettings.swipeToReply.get()) return false
            if (!TapTapBehavior.canHandle(message) || message.type !in MessageTypes.REPLYABLE) return false
            val channel = StoreStream.getChannels().getChannel(message.channelId) ?: return false
            if (channel.isPrivate() && !channel.isSystemDM()) return true
            val permissions = StoreStream.getPermissions().getPermissionsByChannel()[channel.id]
            return PermissionUtils.can(Permission.VIEW_CHANNEL or Permission.SEND_MESSAGES or Permission.READ_MESSAGE_HISTORY, permissions)
        }
    }
}
