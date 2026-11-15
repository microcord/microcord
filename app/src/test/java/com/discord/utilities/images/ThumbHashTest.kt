package com.discord.utilities.images

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/** Expected pixels come from the reference JavaScript decoder (evanw/thumbhash). */
class ThumbHashTest {

    private fun check(hash: String, w: Int, h: Int, vararg pixels: Long) {
        val image = ThumbHash.decode(Base64.getDecoder().decode(hash))
        assertEquals(w, image.width)
        assertEquals(h, image.height)
        val at = listOf(0 to 0, w / 2 to h / 2, w - 1 to h - 1).map { (x, y) -> image.argb[y * w + x].toLong() and 0xFFFFFFFF }
        assertEquals(pixels.toList(), at)
    }

    @Test
    fun portraitMatchesTheReferenceDecoder() =
        check("1QcSHQRnh493V4dIh4eXh1h4kJUI", 23, 32, 0xFF404D71, 0xFF8C6D58, 0xFF000427)

    @Test
    fun landscapeMatchesTheReferenceDecoder() =
        check("3PcNNYSFeXh/d3eld0iHZoZgVwh2", 32, 23, 0xFF2F529A, 0xFF6E7273, 0xFF4C390A)
}
