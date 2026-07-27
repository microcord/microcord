package com.discord.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the player's cache and buffer tuning to the values recovered from the reference DEX
 * (`b.a.p.i`: `104857600`, `DefaultAllocator(true, 65536)`, `10000/30000/2500/5000`).
 *
 * These are not arbitrary: the low playback thresholds are what make an inline GIF start almost
 * immediately, the buffer window is what keeps memory bounded with a dozen of them on screen, and
 * the cache size is what stops the same media being re-downloaded every time it scrolls past. They
 * are also invisible when wrong — playback still "works", just worse — so they get a test.
 */
class AppMediaPlayerConfigTest {

    @Test
    fun `media cache is 100 MB`() {
        assertEquals(104_857_600L, SimpleCacheProvider.MAX_CACHE_BYTES)
    }

    @Test
    fun `load control buffer durations match the reference`() {
        assertEquals(10_000, AppMediaPlayer.MIN_BUFFER_MS)
        assertEquals(30_000, AppMediaPlayer.MAX_BUFFER_MS)
        assertEquals(2_500, AppMediaPlayer.BUFFER_FOR_PLAYBACK_MS)
        assertEquals(5_000, AppMediaPlayer.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
    }

    @Test
    fun `allocator block size matches the reference`() {
        assertEquals(65_536, AppMediaPlayer.ALLOCATION_SIZE_BYTES)
    }
}
