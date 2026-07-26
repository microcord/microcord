package com.discord.utilities.view.grid

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.MainThread
import com.discord.R

/**
 * A frame that tiles its children into a grid it computes itself, keyed by data identity.
 *
 * Unlike a `RecyclerView` this keeps one child per [Data] for as long as that datum is in the
 * dataset, so a child holding expensive state — a video renderer, say — is never recycled onto a
 * different participant. [setData] reconciles the current children against the new dataset:
 * children whose id disappeared are told to [DataView.onRemove] and detached, missing children are
 * created through [Data.createView], and every child is then re-bound.
 *
 * Layout is not a `GridLayout`: [SizeCalculator] splits the frame along its *longer* axis, packing
 * up to `fgl_max_linear_views` tiles in a single line and otherwise wrapping to two lines, with a
 * trailing odd tile stretched to a double span. Sizing happens in both `onMeasure` and `onLayout`
 * because the children's own measure specs are derived from the frame's final size.
 *
 * Every child must implement [DataView]; the class casts unconditionally, as the reference does.
 */
class FrameGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** One tile's worth of data. [id] is the identity [setData] reconciles on. */
    interface Data {
        val id: String
        fun createView(context: Context): View
    }

    /** Implemented by the child views; [getDataId] is `null` while a child is unbound. */
    interface DataView {
        fun getDataId(): String?
        fun onBind(data: Data)
        fun onRemove() = Unit
    }

    // The reference declares a `FrameGridLayout` styleable in its `com.discord.utils` library
    // module; the recovered resources carry the attr on its own, so it is read directly — the same
    // way `VideoCallParticipantView` reads `vcpv_is_overlay`.
    private val maxLinearViews: Int = if (attrs != null) {
        context.obtainStyledAttributes(attrs, intArrayOf(R.attr.fgl_max_linear_views), 0, 0).run {
            try {
                getInt(0, DEFAULT_MAX_LINEAR_VIEWS)
            } finally {
                recycle()
            }
        }
    } else {
        DEFAULT_MAX_LINEAR_VIEWS
    }

    private val sizeCalculator = SizeCalculator(maxLinearViews)
    private var dataset: List<Data> = emptyList()
    private var onBindView: ((View, Data) -> Unit)? = null

    @MainThread
    fun setData(data: List<Data>) {
        dataset = data
        removeStaleViews()
        addNecessaryViews()
        bindViews()
    }

    /** Extra binding the host wants applied after each child's own [DataView.onBind]. */
    @MainThread
    fun setOnBindView(onBindView: ((View, Data) -> Unit)?) {
        this.onBindView = onBindView
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        sizeChildViews { view, bounds ->
            view.measure(
                MeasureSpec.makeMeasureSpec(bounds.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(bounds.height, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        sizeChildViews { view, bounds ->
            view.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
    }

    private fun removeStaleViews() {
        val liveIds = dataset.map { it.id }.toSet()
        for (index in childCount - 1 downTo 0) {
            val child = getChildAt(index) as DataView
            if (child.getDataId() !in liveIds) {
                child.onRemove()
                removeViewAt(index)
            }
        }
    }

    private fun addNecessaryViews() {
        for (index in childCount until dataset.size) {
            addView(dataset[index].createView(context))
        }
    }

    /**
     * Data already on screen keeps its own child; the rest are handed the leftover children in
     * order, so an incoming datum reuses a view that was just vacated rather than a fresh one.
     */
    private fun bindViews() {
        val boundChildren = mutableMapOf<String, View>()
        val unboundChildren = mutableListOf<View>()
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val dataId = (child as DataView).getDataId()
            if (dataId == null) unboundChildren += child else boundChildren[dataId] = child
        }

        dataset.filter { it.id in boundChildren.keys }.forEach { data ->
            boundChildren.getValue(data.id).bind(data)
        }
        dataset.filterNot { it.id in boundChildren.keys }.forEachIndexed { index, data ->
            unboundChildren.getOrNull(index)?.bind(data)
        }
    }

    private fun View.bind(data: Data) {
        val dataView = this as? DataView ?: return
        dataView.onBind(data)
        onBindView?.invoke(this, data)
    }

    private fun sizeChildViews(onSizeChildView: (View, PositionSpec) -> Unit) {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val dataId = (child as DataView).getDataId()
            val itemIndex = dataset.indexOfFirst { it.id == dataId }
            if (itemIndex < 0) continue
            onSizeChildView(
                child,
                sizeCalculator.getItemSize(width, height, childCount, itemIndex),
            )
        }
    }

    internal data class PositionSpec(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width = right - left
        val height = bottom - top
    }

    /**
     * Splits the frame into tiles.
     *
     * The frame's longer axis is the one that gets subdivided: up to [maxLinearViews] tiles sit in
     * a single line, beyond that the tiles wrap onto two lines. When the count is odd the final
     * tile spans two cells so no gap is left.
     */
    internal class SizeCalculator(private val maxLinearViews: Int) {

        fun getItemSize(
            layoutWidth: Int,
            layoutHeight: Int,
            itemCount: Int,
            itemIndex: Int,
        ): PositionSpec {
            val isVertical = layoutHeight >= layoutWidth
            val row = getItemRow(itemIndex, itemCount, isVertical)
            val column = getItemRow(itemIndex, itemCount, !isVertical)
            val rowCount = getRowCount(itemCount, isVertical)
            val columnCount = getRowCount(itemCount, !isVertical)

            val isTrailingOddItem =
                itemIndex == itemCount - 1 && itemCount > maxLinearViews && itemCount % 2 == 1

            val cellWidth = layoutWidth / columnCount
            val cellHeight = layoutHeight / rowCount
            val itemWidth = if (isTrailingOddItem && isVertical) cellWidth * 2 else cellWidth
            val itemHeight = if (isTrailingOddItem && !isVertical) cellHeight * 2 else cellHeight

            val left = column * cellWidth
            val top = row * cellHeight
            return PositionSpec(left, top, itemWidth + left, itemHeight + top)
        }

        private fun getItemRow(itemIndex: Int, itemCount: Int, isVertical: Boolean): Int = when {
            isVertical -> if (itemCount > maxLinearViews) itemIndex / 2 else itemIndex
            itemCount <= maxLinearViews -> 0
            else -> itemIndex % 2
        }

        private fun getRowCount(itemCount: Int, isVertical: Boolean): Int = when {
            isVertical -> when {
                itemCount <= maxLinearViews -> itemCount
                itemCount % 2 == 1 -> itemCount / 2 + 1
                else -> itemCount / 2
            }
            itemCount <= maxLinearViews -> 1
            else -> 2
        }
    }

    private companion object {
        const val DEFAULT_MAX_LINEAR_VIEWS = 3
    }
}
