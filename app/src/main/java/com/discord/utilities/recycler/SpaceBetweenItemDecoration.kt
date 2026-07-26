package com.discord.utilities.recycler

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.ShapeDrawable
import android.util.TypedValue
import android.view.View
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView

/**
 * A [DividerItemDecoration] that inserts a fixed gap *between* rows only, never after the last one.
 *
 * The gap is drawn as a solid shape so it can be tinted; [android.R.color.transparent] gives plain
 * spacing.
 */
class SpaceBetweenItemDecoration(
    context: Context,
    orientation: Int,
    private val adapter: RecyclerView.Adapter<*>,
    private val sizePx: Int = dpToPixels(context, DEFAULT_SIZE_DP),
    @ColorRes private val color: Int = android.R.color.transparent,
) : DividerItemDecoration(context, orientation) {

    init {
        val spacer = ShapeDrawable().apply {
            intrinsicWidth = sizePx
            intrinsicHeight = sizePx
            paint.color = ContextCompat.getColor(context, color)
        }
        setDrawable(spacer)
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        if (parent.getChildAdapterPosition(view) != adapter.itemCount - 1) {
            super.getItemOffsets(outRect, view, parent, state)
        }
    }

    private companion object {
        private const val DEFAULT_SIZE_DP = 16f

        private fun dpToPixels(context: Context, dp: Float): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        ).toInt()
    }
}
