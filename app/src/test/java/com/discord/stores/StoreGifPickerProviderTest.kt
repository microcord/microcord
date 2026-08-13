package com.discord.stores

import org.junit.Assert.assertEquals
import org.junit.Test

class StoreGifPickerProviderTest {
    @Test
    fun giphyResponseMapsOriginalImageAndShareUrl() {
        val gifs = parseGiphyResponse(
            """{"data":[{"url":"https://giphy.com/gifs/example","images":{"original":{"url":"https://media.giphy.com/example.gif","width":"480","height":"270"}}}]}""",
        )

        assertEquals(1, gifs.size)
        assertEquals("https://media.giphy.com/example.gif", gifs.single().gifImageUrl)
        assertEquals("https://giphy.com/gifs/example", gifs.single().tenorGifUrl)
        assertEquals(480, gifs.single().width)
        assertEquals(270, gifs.single().height)
    }

    @Test
    fun tenorResponseMapsGifMediaAndItemUrl() {
        val gifs = parseTenorResponse(
            """{"results":[{"itemurl":"https://tenor.com/view/example","media":[{"tinygif":{"url":"https://media.tenor.com/example-tiny.gif","dims":[220,124]},"gif":{"url":"https://media.tenor.com/example.gif","dims":[320,180]}}]}]}""",
        )

        assertEquals(1, gifs.size)
        assertEquals("https://media.tenor.com/example-tiny.gif", gifs.single().gifImageUrl)
        assertEquals("https://tenor.com/view/example", gifs.single().tenorGifUrl)
        assertEquals(220, gifs.single().width)
        assertEquals(124, gifs.single().height)
    }
}
