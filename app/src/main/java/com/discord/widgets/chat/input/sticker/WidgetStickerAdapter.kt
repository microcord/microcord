package com.discord.widgets.chat.input.sticker

import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppComponent
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import rx.subjects.BehaviorSubject

/**
 * The sticker grid: section headers spanning the full width, sticker tiles one column each.
 *
 * The column count is derived from the recycler's own width so the tiles keep their intended size
 * on any screen, and it is the same number the span-size lookup gives headers.
 *
 * Two deliberate gaps against the reference, both because the type they need lives in the
 * unreconstructed `widgets/chat/input/expression` package:
 *  - it does not extend `WidgetExpressionPickerAdapter`, so there is **no sticky header** pinned to
 *    the top of the grid while scrolling through a section;
 *  - there is no `ExpressionPickerItemDecoration`, so section spacing comes from the item layouts
 *    alone.
 * Neither affects which stickers are shown or what tapping one does.
 */
class WidgetStickerAdapter(
    recycler: RecyclerView,
    private val onStickerItemSelected: (StickerItem) -> Unit,
    private val onStickerHeaderItemClicked: ((StoreHeaderItem) -> Unit)? = null,
    private val recyclerScrollingWithinThresholdSubject: BehaviorSubject<Boolean>? = null,
    internal val appComponent: AppComponent,
    internal val showStickerPackDescriptions: Boolean = false,
) : MGRecyclerAdapterSimple<MGRecyclerDataPayload>(recycler) {

    val numColumns: Int = calculateNumOfColumns(
        recycler,
        recycler.context.resources.getDimension(R.dimen.chat_input_sticker_size),
        DEFAULT_NUM_COLUMNS,
    )

    val gridLayoutManager: GridLayoutManager = GridLayoutManager(recycler.context, numColumns).apply {
        spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                when (getItemViewType(position)) {
                    ITEM_TYPE_OWNED_HEADER, ITEM_TYPE_STORE_HEADER -> numColumns
                    else -> 1
                }
        }
    }

    /** Set by the host so a scroll can move the category rail's selection. */
    var onScrollPositionListener: ((Int) -> Unit)? = null

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            val first = gridLayoutManager.findFirstVisibleItemPosition()
            if (first != RecyclerView.NO_POSITION) onScrollPositionListener?.invoke(first)
        }
    }

    init {
        recycler.layoutManager = gridLayoutManager
        recycler.adapter = this
        recycler.addOnScrollListener(scrollListener)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, MGRecyclerDataPayload> = when (viewType) {
        ITEM_TYPE_OWNED_HEADER -> OwnedHeaderViewHolder(this)
        ITEM_TYPE_STICKER ->
            StickerViewHolder(this, recyclerScrollingWithinThresholdSubject, onStickerItemSelected)
        ITEM_TYPE_STORE_HEADER -> StoreHeaderViewHolder(this, onStickerHeaderItemClicked)
        else -> throw invalidViewTypeException(viewType)
    }

    /**
     * Jumps without animating and without letting the resulting scroll move the category selection
     * — otherwise tapping a tab would fight the scroll listener all the way to the target.
     */
    fun scrollToPosition(position: Int) {
        recycler.removeOnScrollListener(scrollListener)
        gridLayoutManager.scrollToPositionWithOffset(position, 0)
        recycler.addOnScrollListener(scrollListener)
    }

    companion object {
        const val DEFAULT_NUM_COLUMNS = 4
        const val ITEM_TYPE_OWNED_HEADER = 0
        const val ITEM_TYPE_STICKER = 1
        const val ITEM_TYPE_STORE_HEADER = 2

        /**
         * How many [itemSizePx]-wide tiles fit across [recyclerView], falling back to [fallback]
         * before the recycler has been measured.
         */
        fun calculateNumOfColumns(
            recyclerView: RecyclerView,
            itemSizePx: Float,
            fallback: Int,
        ): Int {
            if (itemSizePx <= 0f) return fallback
            val margins = (recyclerView.layoutParams as? ViewGroup.MarginLayoutParams)
                ?.let { it.marginStart + it.marginEnd } ?: 0
            val available = recyclerView.context.resources.displayMetrics.widthPixels - margins
            return (available / itemSizePx).toInt().takeIf { it > 0 } ?: fallback
        }
    }
}
