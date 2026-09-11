package com.discord.widgets.botuikit

import com.discord.widgets.botuikit.views.v2.MediaGalleryLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGalleryLayoutTest {

    private val width = 300
    private val gap = 4
    private val singleMaxHeight = 330

    private fun layout(count: Int, sourceWidth: Int = 0, sourceHeight: Int = 0) =
        MediaGalleryLayout.layout(count, width, gap, singleMaxHeight, sourceWidth, sourceHeight)

    @Test
    fun emptyGalleryOccupiesNothing() {
        assertTrue(layout(0).isEmpty)
        assertEquals(0, layout(0).height)
    }

    @Test
    fun anOversizedLandscapeSingleShrinksToTheRow() {
        val grid = layout(1, sourceWidth = 800, sourceHeight = 400)
        assertEquals(MediaGalleryLayout.Cell(0, 0, width, 150), grid.cells.single())
        assertEquals(150, grid.height)
    }

    @Test
    fun anUndersizedSingleKeepsItsOwnSizeInsteadOfBeingBlownUp() {
        val grid = layout(1, sourceWidth = 120, sourceHeight = 80)
        assertEquals(MediaGalleryLayout.Cell(0, 0, 120, 80), grid.cells.single())
    }

    @Test
    fun portraitSingleIsCappedByHeightNotWidth() {
        // 1:2 at the 330 cap would be 165 wide, which fits, so the cap decides.
        val grid = layout(1, sourceWidth = 500, sourceHeight = 1000)
        assertEquals(MediaGalleryLayout.Cell(0, 0, 165, 330), grid.cells.single())
    }

    @Test
    fun aPortraitSingleWiderThanTheRowIsBoundedByTheRow() {
        // 19:20 capped at 330 tall would be 314 wide, which overflows the 300 row.
        val grid = layout(1, sourceWidth = 950, sourceHeight = 1000)
        assertEquals(width, grid.cells.single().width)
        assertTrue(grid.cells.single().height <= singleMaxHeight)
    }

    @Test
    fun aShortPortraitSingleIsNotStretchedToTheCap() {
        val grid = layout(1, sourceWidth = 100, sourceHeight = 200)
        assertEquals(MediaGalleryLayout.Cell(0, 0, 100, 200), grid.cells.single())
    }

    @Test
    fun anUnresolvedSingleGetsTheDefaultRowShape() {
        val grid = layout(1)
        assertEquals(width, grid.cells.single().width)
        assertEquals(200, grid.cells.single().height)
    }

    @Test
    fun twoItemsSitSideBySideAtThreeQuarters() {
        val grid = layout(2)
        val cellWidth = (width - gap) / 2
        assertEquals(listOf(0, cellWidth + gap), grid.cells.map { it.left })
        assertTrue(grid.cells.all { it.top == 0 && it.width == cellWidth })
        assertEquals((cellWidth / 0.75f).toInt(), grid.cells[0].height)
        assertEquals(grid.cells[0].height, grid.height)
    }

    @Test
    fun threeItemsBecomeALargeCellWithTwoStackedBesideIt() {
        val grid = layout(3)
        val trailingWidth = (width - gap) / 3
        val leadingWidth = width - gap - trailingWidth
        assertEquals(MediaGalleryLayout.Cell(0, 0, leadingWidth, leadingWidth), grid.cells[0])
        assertEquals(leadingWidth + gap, grid.cells[1].left)
        assertEquals(trailingWidth, grid.cells[1].width)
        // The stack exactly fills the tall cell beside it, gap included.
        assertEquals(grid.height, grid.cells[2].top + grid.cells[2].height)
        assertEquals(width, grid.cells[1].left + grid.cells[1].width)
    }

    @Test
    fun fourItemsBecomeTwoRowsOfTwo() {
        val grid = layout(4)
        val cellWidth = (width - gap) / 2
        assertEquals(listOf(0, cellWidth + gap, 0, cellWidth + gap), grid.cells.map { it.left })
        assertEquals(2, grid.cells.map { it.top }.distinct().size)
        assertTrue(grid.cells.all { it.width == cellWidth })
        assertEquals(width, grid.cells[1].left + grid.cells[1].width)
    }

    @Test
    fun theRaggedRowGoesFirst() {
        // Five is a pair above a triple, not a triple above a pair.
        val five = layout(5)
        assertEquals(2, five.cells.count { it.top == 0 })
        val secondRowTop = five.cells.map { it.top }.distinct().sorted()[1]
        assertEquals(3, five.cells.count { it.top == secondRowTop })

        // Seven leads with a lone full-width item.
        val seven = layout(7)
        assertEquals(1, seven.cells.count { it.top == 0 })
        assertEquals(width, seven.cells[0].width)
    }

    @Test
    fun fullRowsHoldSquareCellsThatSpanTheWidth() {
        val grid = layout(6)
        val cellWidth = (width - gap * 2) / 3
        assertTrue(grid.cells.all { it.width == cellWidth && it.height == cellWidth })
        assertEquals(2, grid.cells.map { it.top }.distinct().size)
    }

    @Test
    fun everyLayoutStaysInsideTheRowAndAccountsForItsOwnHeight() {
        for (count in 1..12) {
            val grid = layout(count, sourceWidth = 800, sourceHeight = 500)
            assertEquals("cell count for $count", count, grid.cells.size)
            grid.cells.forEach { cell ->
                assertTrue("$count overflows width", cell.left + cell.width <= width)
                assertTrue("$count overflows height", cell.top + cell.height <= grid.height)
                assertTrue("$count has an empty cell", cell.width > 0 && cell.height > 0)
            }
            assertTrue("$count reports no height", grid.height > 0)
        }
    }
}
