package com.discord.widgets.voice.fullscreen.grid

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * A [GridLayoutManager] that sizes every child to a share of the recycler rather than letting the
 * children measure themselves, so a call grid always fills the screen exactly.
 *
 * The divisor is `itemCount / 2 + itemCount % 2` — two tiles per line, rounding up — except for the
 * two-participant case, which is pinned to 2 so a one-on-one call splits the screen evenly instead
 * of giving each tile the whole thing. The result is clamped so a very large call never shrinks
 * tiles past [maxRows]/[maxColumns].
 *
 * Scrolling is restricted to the layout orientation; the cross axis is always fully visible.
 */
open class ResizingGridLayoutManager(
    context: Context,
    spanCount: Int,
    orientation: Int,
) : GridLayoutManager(context, spanCount, orientation, false) {

    private val horizontalSpace: Int get() = width - paddingRight - paddingLeft
    private val verticalSpace: Int get() = height - paddingBottom - paddingTop

    override fun canScrollHorizontally(): Boolean = orientation == HORIZONTAL

    override fun canScrollVertically(): Boolean = orientation == VERTICAL

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams =
        spanLayoutSize(super.generateDefaultLayoutParams())

    override fun generateLayoutParams(c: Context, attrs: AttributeSet): RecyclerView.LayoutParams =
        spanLayoutSize(super.generateLayoutParams(c, attrs))

    override fun generateLayoutParams(lp: ViewGroup.LayoutParams): RecyclerView.LayoutParams =
        spanLayoutSize(super.generateLayoutParams(lp))

    /**
     * Children whose size no longer matches the current split are treated as invalid, which is what
     * forces the recycler to re-generate their params after the participant count changes.
     */
    override fun checkLayoutParams(lp: RecyclerView.LayoutParams): Boolean {
        val expected = generateDefaultLayoutParams()
        return super.checkLayoutParams(lp) && expected.width == lp.width && expected.height == lp.height
    }

    private fun spanLayoutSize(layoutParams: RecyclerView.LayoutParams): RecyclerView.LayoutParams {
        when (orientation) {
            HORIZONTAL -> layoutParams.width =
                (horizontalSpace / lineCount()).coerceAtLeast(horizontalSpace / maxColumns())
            VERTICAL -> layoutParams.height =
                (verticalSpace / lineCount()).coerceAtLeast(verticalSpace / maxRows())
        }
        return layoutParams
    }

    private fun lineCount(): Int = Companion.lineCount(itemCount)

    private fun maxColumns() = MAX_SPAN
    private fun maxRows() = MAX_SPAN

    internal companion object {
        private const val MAX_SPAN = 4

        /**
         * Lines the participants are split across: two per line, rounding up. A two-participant
         * call is pinned to two lines so a one-on-one splits the screen instead of one tile taking
         * all of it.
         */
        internal fun lineCount(itemCount: Int): Int =
            if (itemCount == 2) 2 else itemCount % 2 + itemCount / 2
    }
}
