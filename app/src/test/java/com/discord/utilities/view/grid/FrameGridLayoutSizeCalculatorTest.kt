package com.discord.utilities.view.grid

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the tiling arithmetic, which was recovered from decompiled bytecode and has no other
 * coverage. The default `maxLinearViews` of 3 is what every reference call site uses.
 */
class FrameGridLayoutSizeCalculatorTest {

    private val calculator = FrameGridLayout.SizeCalculator(maxLinearViews = 3)

    private fun sizeOf(width: Int, height: Int, itemCount: Int, itemIndex: Int) =
        calculator.getItemSize(width, height, itemCount, itemIndex)

    @Test
    fun `a single tile fills the frame`() {
        assertEquals(
            FrameGridLayout.PositionSpec(0, 0, 400, 800),
            sizeOf(width = 400, height = 800, itemCount = 1, itemIndex = 0),
        )
    }

    @Test
    fun `a portrait frame stacks up to three tiles vertically`() {
        // Taller than wide, so the vertical axis is the one subdivided.
        assertEquals(
            FrameGridLayout.PositionSpec(0, 0, 400, 300),
            sizeOf(width = 400, height = 900, itemCount = 3, itemIndex = 0),
        )
        assertEquals(
            FrameGridLayout.PositionSpec(0, 600, 400, 900),
            sizeOf(width = 400, height = 900, itemCount = 3, itemIndex = 2),
        )
    }

    @Test
    fun `a landscape frame lays up to three tiles side by side`() {
        assertEquals(
            FrameGridLayout.PositionSpec(0, 0, 300, 400),
            sizeOf(width = 900, height = 400, itemCount = 3, itemIndex = 0),
        )
        assertEquals(
            FrameGridLayout.PositionSpec(600, 0, 900, 400),
            sizeOf(width = 900, height = 400, itemCount = 3, itemIndex = 2),
        )
    }

    @Test
    fun `beyond the linear limit tiles wrap onto two columns`() {
        // Four tiles in a portrait frame: two columns, two rows.
        assertEquals(
            FrameGridLayout.PositionSpec(0, 0, 200, 400),
            sizeOf(width = 400, height = 800, itemCount = 4, itemIndex = 0),
        )
        assertEquals(
            FrameGridLayout.PositionSpec(200, 0, 400, 400),
            sizeOf(width = 400, height = 800, itemCount = 4, itemIndex = 1),
        )
        assertEquals(
            FrameGridLayout.PositionSpec(0, 400, 200, 800),
            sizeOf(width = 400, height = 800, itemCount = 4, itemIndex = 2),
        )
    }

    @Test
    fun `an odd trailing tile spans both columns so no gap is left`() {
        // Five tiles in a portrait frame: three rows, and the last tile takes the whole width.
        val last = sizeOf(width = 400, height = 900, itemCount = 5, itemIndex = 4)
        assertEquals(400, last.width)
        assertEquals(0, last.left)
        assertEquals(FrameGridLayout.PositionSpec(0, 600, 400, 900), last)
    }
}
