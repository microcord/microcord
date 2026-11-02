package com.discord.utilities.feature

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadableNameColorTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `a dark colour on black is scaled up keeping its hue`() {
        assertEquals(0xFF303000.toInt(), readableNameColor(0xFF282800.toInt(), black))
    }

    @Test
    fun `a colour with no lift is lifted evenly`() {
        assertEquals(0xFF202020.toInt(), readableNameColor(black, black))
    }

    @Test
    fun `a readable colour is left alone`() {
        assertEquals(0xFF00FF00.toInt(), readableNameColor(0xFF00FF00.toInt(), black))
    }

    @Test
    fun `on a dark grey background the distance from that background is what counts`() {
        assertEquals(0xFF404040.toInt(), readableNameColor(0xFF303030.toInt(), 0xFF202020.toInt()))
    }

    @Test
    fun `a light background pushes towards black`() {
        assertEquals(0xFFDFDFDF.toInt(), readableNameColor(0xFFF0F0F0.toInt(), white))
    }
}
