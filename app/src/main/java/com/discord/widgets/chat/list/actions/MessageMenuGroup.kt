package com.discord.widgets.chat.list.actions

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import androidx.core.view.children
import com.discord.R
import com.discord.views.ThemedColor

/**
 * One rounded card of the modern message menu: its visible rows stacked, a hairline between them
 * that starts under the label, and gone entirely when every row is hidden. Rows show and hide
 * themselves, so call [refresh] once they have settled.
 */
class MessageMenuGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private val radius = RADIUS_DP * density
    private val dividerInset = DIVIDER_INSET_DP * density
    private val divider = Paint().apply {
        color = ThemedColor.resolve(context, R.attr.colorBackgroundModifierAccent)
        strokeWidth = maxOf(1f, density * 0.5f)
    }

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.message_menu_group_bg)
        setWillNotDraw(false)
        // Row ripples must not square off the card's corners.
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    fun refresh() {
        visibility = if (children.any { it.visibility == VISIBLE }) VISIBLE else GONE
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val visible = children.filter { it.visibility == VISIBLE }.toList()
        for (row in visible.dropLast(1)) {
            val y = row.bottom - divider.strokeWidth / 2
            canvas.drawLine(dividerInset, y, width.toFloat(), y, divider)
        }
    }

    private companion object {
        const val RADIUS_DP = 16
        // Icon column: 16dp padding + 24dp icon + 16dp gap.
        const val DIVIDER_INSET_DP = 56
    }
}
