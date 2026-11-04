package com.discord.rtcconnection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSinkWantsManagerLadderTest {
    @Test
    fun `a full quality want encodes at 720p and 2_5 mbps`() {
        val quality = MediaSinkWantsManager.encodingQualityForWant(100)
        assertEquals(1280, quality.width)
        assertEquals(720, quality.height)
        assertEquals(2_500_000, quality.maxBitrate)
    }

    @Test
    fun `a middling want encodes at the matching rung`() {
        val quality = MediaSinkWantsManager.encodingQualityForWant(50)
        assertEquals(416, quality.width)
        assertEquals(234, quality.height)
    }

    @Test
    fun `a low want falls back to the smallest rung`() {
        val quality = MediaSinkWantsManager.encodingQualityForWant(10)
        assertEquals(256, quality.width)
        assertEquals(144, quality.height)
    }

    @Test
    fun `resolution never drops as the want rises`() {
        val heights = (0..100 step 10).map { MediaSinkWantsManager.encodingQualityForWant(it).height }
        assertTrue(heights.zipWithNext().all { (low, high) -> low <= high })
    }
}
