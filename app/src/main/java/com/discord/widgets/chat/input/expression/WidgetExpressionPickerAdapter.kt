package com.discord.widgets.chat.input.expression

import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MarginLayoutParamsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.app.AppLog
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.rx.ui
import com.discord.utilities.views.StickyHeaderItemDecoration
import com.discord.widgets.chat.input.ExpressionPickerItemDecoration
import java.util.concurrent.TimeUnit
import rx.subjects.PublishSubject

/**
 * Base for the emoji and sticker grids: a flat, sectioned list with a pinned header and a scroll
 * position that the category rail follows.
 *
 * Three things are shared and none of them is trivial to get right twice:
 *
 *  - **Header bookkeeping.** [setData] recomputes the positions of every header row, which both the
 *    sticky-header decoration and [ExpressionPickerItemDecoration] index into. Recomputing here,
 *    once per data set, is why neither decoration has to scan the list per frame.
 *  - **A throttled scroll report.** The category rail needs the first visible position, but not 60
 *    times a second; the report is throttled on the leading edge so the first pixel of a scroll
 *    updates the rail immediately and the rest of the gesture collapses into one more update.
 *  - **Silent scrolls.** [scrollToPosition] detaches the scroll listener for the duration, so a
 *    programmatic scroll started *by* the rail does not feed back into the rail and fight it.
 *
 * Subclasses supply the grid's [layoutManager] and [numColumns], and the single view that gets
 * re-bound as the pinned header ([createStickyHeaderViewHolder]).
 */
abstract class WidgetExpressionPickerAdapter(
    recycler: RecyclerView,
    /** View types that count as section headers. */
    private val headerTypes: Set<Int>,
) : MGRecyclerAdapterSimple<MGRecyclerDataPayload>(recycler),
    StickyHeaderItemDecoration.StickyHeaderAdapter {

    abstract val layoutManager: GridLayoutManager

    abstract val numColumns: Int

    abstract fun createStickyHeaderViewHolder(adapter: WidgetExpressionPickerAdapter): StickyHeaderViewHolder

    /** Adapter positions of the header rows, ascending. Empty until the first [setData]. */
    var headerIndices: List<Int> = emptyList()
        private set

    var onScrollPositionListener: (Int) -> Unit = {}

    private var itemDecoration: ExpressionPickerItemDecoration? = null
    private var stickyHeaderManager: StickyHeadersManager? = null

    private val scrollEvents = PublishSubject.create<Unit>()

    private val onScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)
            scrollEvents.onNext(Unit)
        }
    }

    init {
        scrollEvents
            .leadingEdgeThrottle(SCROLL_THROTTLE_MS, TimeUnit.MILLISECONDS)
            .ui()
            .subscribe(
                { onScrollPositionListener(layoutManager.findFirstVisibleItemPosition()) },
                { AppLog.w("expression picker scroll observation ended", it) },
            )
        recycler.addOnScrollListener(onScrollListener)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        val manager = StickyHeadersManager(this)
        stickyHeaderManager = manager
        // The pinned header is outside the layout pass, so it has to be re-measured whenever the
        // recycler's own bounds change (rotation, keyboard, the tray's app bar collapsing).
        recyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            manager.layoutViews(recyclerView)
        }
    }

    override fun setData(data: List<MGRecyclerDataPayload>) {
        super.setData(data)
        headerIndices = data.mapIndexedNotNull { index, item -> index.takeIf { item.type in headerTypes } }

        itemDecoration?.let(recycler::removeItemDecoration)
        itemDecoration = ExpressionPickerItemDecoration(
            numColumns,
            headerIndices,
            (SECTION_BOTTOM_PADDING_DP * recycler.resources.displayMetrics.density).toInt(),
        ).also(recycler::addItemDecoration)
    }

    /** Scrolls without telling the scroll listener, so the caller's own selection is not overwritten. */
    fun scrollToPosition(position: Int) {
        recycler.removeOnScrollListener(onScrollListener)
        layoutManager.scrollToPositionWithOffset(position, 0)
        recycler.addOnScrollListener(onScrollListener)
    }

    // region StickyHeaderAdapter

    override fun getAndBindHeaderView(position: Int): View? {
        val manager = stickyHeaderManager ?: return null
        manager.stickyHeaderHolder.bind(position, getItem(position))
        return manager.currentStickyHeaderView
    }

    override fun getHeaderPositionForItem(itemPosition: Int): Int? {
        val headerPosition = headerIndices.lastOrNull { itemPosition >= it }
        if (headerPosition == null) {
            AppLog.w("failed to find header position for item in ${javaClass.name}")
        }
        return headerPosition
    }

    override fun isHeader(position: Int): Boolean = position in headerIndices

    // endregion

    /** The pinned header's view, plus the binding hook the decoration calls into. */
    interface StickyHeaderViewHolder {
        fun bind(position: Int, data: MGRecyclerDataPayload)

        fun getItemView(): View
    }

    class StickyHeadersManager(adapter: WidgetExpressionPickerAdapter) {
        val stickyHeaderHolder: StickyHeaderViewHolder = adapter.createStickyHeaderViewHolder(adapter)
        val currentStickyHeaderView: View? = stickyHeaderHolder.getItemView()

        fun layoutViews(recyclerView: RecyclerView) {
            currentStickyHeaderView?.let {
                StickyHeaderItemDecoration.LayoutManager.layoutHeaderView(recyclerView, it)
            }
        }
    }

    companion object {
        const val ITEM_TYPE_HEADER = 0

        private const val SCROLL_THROTTLE_MS = 250L
        private const val SECTION_BOTTOM_PADDING_DP = 8

        /**
         * How many [itemSize]-wide tiles fit across [resources]' display once [margin] is removed.
         *
         * Falls back to [defaultNumColumns] rather than dividing by zero when the measurement is
         * nonsense — which happens if this is called before the recycler has a width.
         */
        @JvmStatic
        fun calculateNumOfColumns(
            margin: Int,
            itemSize: Float,
            defaultNumColumns: Int,
            resources: Resources,
        ): Int {
            val displayMetrics = resources.displayMetrics
            val columns = ((displayMetrics.widthPixels - margin) / itemSize).toInt()
            if (columns != 0) return columns
            AppLog.e(
                """
                  invalid dimensions while calculating numColumns
                  displayMetrics.widthPixels: ${displayMetrics.widthPixels}
                  total margin marginStart: $margin
                  itemSize: $itemSize
                """.trimIndent(),
            )
            return defaultNumColumns
        }

        @JvmStatic
        fun calculateNumOfColumns(
            recyclerView: RecyclerView,
            itemSize: Float,
            defaultNumColumns: Int,
        ): Int {
            val params = recyclerView.layoutParams as? ViewGroup.MarginLayoutParams
            val marginStart = params?.let(MarginLayoutParamsCompat::getMarginStart) ?: 0
            val marginEnd = params?.let(MarginLayoutParamsCompat::getMarginEnd) ?: 0
            return calculateNumOfColumns(
                marginStart + marginEnd,
                itemSize,
                defaultNumColumns,
                recyclerView.resources,
            )
        }
    }
}
