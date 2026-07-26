package com.discord.widgets.voice.fullscreen.grid

import org.junit.Assert.assertEquals
import org.junit.Test

class ResizingGridLayoutManagerTest {

    @Test
    fun `a one-on-one call splits the screen instead of giving one tile all of it`() {
        // The special case: 2 would otherwise round to 1 line via `2 % 2 + 2 / 2`.
        assertEquals(2, ResizingGridLayoutManager.lineCount(itemCount = 2))
    }

    @Test
    fun `participants are packed two per line, rounding up`() {
        assertEquals(0, ResizingGridLayoutManager.lineCount(itemCount = 0))
        assertEquals(1, ResizingGridLayoutManager.lineCount(itemCount = 1))
        assertEquals(2, ResizingGridLayoutManager.lineCount(itemCount = 3))
        assertEquals(2, ResizingGridLayoutManager.lineCount(itemCount = 4))
        assertEquals(3, ResizingGridLayoutManager.lineCount(itemCount = 5))
        assertEquals(5, ResizingGridLayoutManager.lineCount(itemCount = 9))
    }
}
