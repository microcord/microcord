package com.discord.widgets.botuikit.views.v2

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The grid a media gallery's items are laid out on.
 *
 * The gallery is a mosaic rather than a uniform grid: the item count alone decides the shape, and
 * three counts have a layout of their own.
 *
 *  - **one** item keeps its own aspect ratio, shrunk to fit the row and capped at
 *    [SINGLE_MAX_HEIGHT_DP] so a tall image cannot take over the whole viewport.
 *  - **three** items become a large cell on the leading side with two stacked square cells beside
 *    it.
 *  - **four** items become two rows of two, each cell at 3:2.
 *  - **anything else** wraps into rows of at most [MAX_PER_ROW]. The *first* row is the short one
 *    — it takes `count % 3` items (or a full three when the count divides evenly) so the ragged
 *    row sits at the top rather than the bottom. A row's cell shape depends on how full it is: a
 *    lone item is 3:2, a pair is 3:4, and a full row is square.
 *
 * Sizes are in pixels; callers scale [GAP_DP] and [SINGLE_MAX_HEIGHT_DP] before calling.
 */
object MediaGalleryLayout {

    /** Gap between neighbouring cells, horizontally and vertically. */
    const val GAP_DP = 4

    /** A single item never grows taller than this, however tall the source is. */
    const val SINGLE_MAX_HEIGHT_DP = 330

    /** Rows never hold more than this many items. */
    const val MAX_PER_ROW = 3

    /** Aspect ratio (width / height) of a row holding one item. */
    private const val LONE_ITEM_ASPECT = 1.5f

    /** Aspect ratio (width / height) of a row holding two items. */
    private const val PAIR_ASPECT = 0.75f

    /** The position and size one item occupies, relative to the gallery's top-left corner. */
    data class Cell(val left: Int, val top: Int, val width: Int, val height: Int)

    /** A laid-out gallery: where every item goes, and how tall the whole thing ended up. */
    data class Grid(val cells: List<Cell>, val height: Int) {
        val isEmpty: Boolean get() = cells.isEmpty()
    }

    /**
     * Lays [count] items out across [width] pixels.
     *
     * [sourceWidth] and [sourceHeight] are the single item's own size and matter only when [count]
     * is one, because that is the only layout an item's own shape survives into. Pass zero for
     * either when the item has not unfurled, and it falls back to the same 3:2 a lone item in a
     * wrapped row gets. Both are in the same units as [width], so the caller scales the media's
     * pixel dimensions to the display first.
     */
    fun layout(
        count: Int,
        width: Int,
        gap: Int,
        singleMaxHeight: Int,
        sourceWidth: Int = 0,
        sourceHeight: Int = 0,
    ): Grid {
        if (count <= 0 || width <= 0) return Grid(emptyList(), 0)
        return when (count) {
            1 -> single(width, singleMaxHeight, sourceWidth, sourceHeight)
            3 -> three(width, gap)
            4 -> four(width, gap)
            else -> rows(count, width, gap)
        }
    }

    /**
     * One item, at its own aspect ratio.
     *
     * A landscape item is only ever shrunk, never blown up: an item narrower than the row keeps
     * its own width rather than being stretched across it. A portrait one is capped by height
     * first, and only falls back to the row width when honouring that cap would overflow it.
     */
    private fun single(width: Int, maxHeight: Int, sourceWidth: Int, sourceHeight: Int): Grid {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            val height = (width / LONE_ITEM_ASPECT).roundToInt()
            return Grid(listOf(Cell(0, 0, width, height)), height)
        }
        val ratio = sourceWidth.toFloat() / sourceHeight
        val cellWidth: Int
        val cellHeight: Int
        if (ratio >= 1f) {
            cellWidth = min(sourceWidth, width)
            cellHeight = (cellWidth / ratio).roundToInt()
        } else {
            val cappedHeight = min(sourceHeight, maxHeight)
            val cappedWidth = (cappedHeight * ratio).roundToInt()
            if (cappedWidth > width) {
                cellWidth = width
                cellHeight = (width / ratio).roundToInt()
            } else {
                cellWidth = cappedWidth
                cellHeight = cappedHeight
            }
        }
        return Grid(listOf(Cell(0, 0, cellWidth, cellHeight)), cellHeight)
    }

    /** A tall leading cell taking two thirds of the row, with two square cells stacked beside it. */
    private fun three(width: Int, gap: Int): Grid {
        val trailingWidth = (width - gap) / 3
        val leadingWidth = width - gap - trailingWidth
        val height = leadingWidth
        val trailingHeight = (height - gap) / 2
        return Grid(
            listOf(
                Cell(0, 0, leadingWidth, height),
                Cell(leadingWidth + gap, 0, trailingWidth, trailingHeight),
                Cell(leadingWidth + gap, trailingHeight + gap, trailingWidth, height - trailingHeight - gap),
            ),
            height,
        )
    }

    /** Two rows of two, every cell at 3:2. */
    private fun four(width: Int, gap: Int): Grid {
        val cellWidth = (width - gap) / 2
        val cellHeight = (cellWidth / LONE_ITEM_ASPECT).roundToInt()
        val cells = (0 until 4).map { index ->
            Cell(
                left = (index % 2) * (cellWidth + gap),
                top = (index / 2) * (cellHeight + gap),
                width = cellWidth,
                height = cellHeight,
            )
        }
        return Grid(cells, cellHeight * 2 + gap)
    }

    /**
     * Rows of at most [MAX_PER_ROW], the short row first.
     *
     * Putting the remainder at the top is what keeps a five-item gallery reading as a pair above a
     * triple rather than a triple above a stranded pair.
     */
    private fun rows(count: Int, width: Int, gap: Int): Grid {
        val remainder = count % MAX_PER_ROW
        val firstRow = if (remainder == 0) MAX_PER_ROW else remainder
        val cells = ArrayList<Cell>(count)
        var index = 0
        var top = 0
        var rowSize = firstRow
        while (index < count) {
            val size = min(rowSize, count - index)
            val cellWidth = (width - gap * (size - 1)) / size
            val cellHeight = when (size) {
                1 -> (cellWidth / LONE_ITEM_ASPECT).roundToInt()
                2 -> (cellWidth / PAIR_ASPECT).roundToInt()
                else -> cellWidth
            }
            for (column in 0 until size) {
                cells += Cell(column * (cellWidth + gap), top, cellWidth, cellHeight)
            }
            index += size
            top += cellHeight + gap
            rowSize = MAX_PER_ROW
        }
        return Grid(cells, (top - gap).coerceAtLeast(0))
    }
}
