package com.discord.utilities.views

import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import kotlin.math.abs

/**
 * Draws the section header of whatever is currently at the top of the list, pinned over the list.
 *
 * The header is a single view owned by the adapter and re-bound as the list scrolls, not a real
 * row: pinning a real row would take it out of the layout and leave a gap. When the *next* section's
 * header scrolls into contact with the pinned one, the pinned one is translated up by the overlap,
 * so the two appear to push each other rather than cross-fade.
 */
class StickyHeaderItemDecoration(
    private val adapter: StickyHeaderAdapter,
) : RecyclerView.ItemDecoration() {

    /** Bottom edge of the pinned header in list coordinates; `0` when nothing is pinned. */
    private var stickyHeaderBottom: Float = 0f

    private var actionDownRawX: Float? = null
    private var actionDownRawY: Float? = null

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDrawOver(canvas, parent, state)
        val topChild = parent.getChildAt(0) ?: return
        val topPosition = parent.getChildAdapterPosition(topChild)
        if (topPosition == RecyclerView.NO_POSITION) return

        val header = getAndBindHeaderViewForItem(topPosition)
        if (header == null) {
            stickyHeaderBottom = 0f
            return
        }

        val contactPoint = parent.top + header.bottom
        if (parent.top > contactPoint || parent.bottom <= contactPoint) return

        val overlapping = getOverlappingView(parent, header.bottom)
        val offsetY = overlapping
            ?.takeIf { parent.getChildAdapterPosition(it) != RecyclerView.NO_POSITION }
            ?.takeIf { adapter.isHeader(parent.getChildAdapterPosition(it)) }
            ?.let { (it.top - header.height).toFloat() }
            ?: 0f
        drawHeader(canvas, header, offsetY)
    }

    private fun drawHeader(canvas: Canvas, header: View, offsetY: Float) {
        canvas.save()
        if (offsetY != 0f) canvas.translate(0f, offsetY)
        stickyHeaderBottom = header.bottom - offsetY
        header.draw(canvas)
        canvas.restore()
    }

    private fun getAndBindHeaderViewForItem(itemPosition: Int): View? =
        adapter.getHeaderPositionForItem(itemPosition)?.let(adapter::getAndBindHeaderView)

    private fun getOverlappingView(parent: RecyclerView, contactPoint: Int): View? =
        (0 until parent.childCount)
            .map(parent::getChildAt)
            .firstOrNull { it.top <= contactPoint && it.bottom > contactPoint }

    /**
     * Swallows taps that land on the pinned header.
     *
     * Without this the tap falls through to the row drawn *underneath* the header, which is a
     * different section entirely. Movement beyond the scroll slop between down and up is treated as
     * a scroll, not a tap, and is let through.
     */
    fun blockClicks(parent: RecyclerView) {
        parent.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(recyclerView: RecyclerView, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        actionDownRawX = event.rawX
                        actionDownRawY = event.rawY
                    }

                    MotionEvent.ACTION_UP -> {
                        val downX = actionDownRawX
                        val downY = actionDownRawY
                        val slop = recyclerView.resources.getDimensionPixelSize(R.dimen.default_scroll_slop)
                        val isTap = downX != null && downY != null &&
                            abs(event.rawX - downX) < slop &&
                            abs(event.rawY - downY) < slop
                        resetActionDownCoordinates()
                        if (isTap && event.y <= stickyHeaderBottom) return true
                    }

                    MotionEvent.ACTION_CANCEL -> resetActionDownCoordinates()
                }
                return false
            }
        })
    }

    private fun resetActionDownCoordinates() {
        actionDownRawX = null
        actionDownRawY = null
    }

    /** The pinned header lives outside the recycler's layout pass, so it is measured by hand. */
    object LayoutManager {
        @JvmStatic
        fun layoutHeaderView(parent: ViewGroup, view: View) {
            view.measure(
                ViewGroup.getChildMeasureSpec(
                    View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY),
                    parent.paddingLeft + parent.paddingRight,
                    view.layoutParams.width,
                ),
                ViewGroup.getChildMeasureSpec(
                    View.MeasureSpec.makeMeasureSpec(parent.height, View.MeasureSpec.UNSPECIFIED),
                    parent.paddingTop + parent.paddingBottom,
                    view.layoutParams.height,
                ),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
    }

    interface StickyHeaderAdapter {
        /** Binds the shared header view to [position] and returns it; `null` if there is none. */
        fun getAndBindHeaderView(position: Int): View?

        /** The header governing [itemPosition], or `null` when the item precedes every header. */
        fun getHeaderPositionForItem(itemPosition: Int): Int?

        fun isHeader(position: Int): Boolean
    }
}
