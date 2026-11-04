package com.discord.widgets.media

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryWindowTest {
    private val items = (0 until 1000).toList()

    @Test
    fun `a small gallery is passed whole`() {
        val (window, selected) = galleryWindow(items.take(10), 4)
        assertEquals(10, window.size)
        assertEquals(4, selected)
    }

    @Test
    fun `a huge gallery is cut to a window around the selection`() {
        val (window, selected) = galleryWindow(items, 500, max = 60)
        assertEquals(60, window.size)
        assertEquals(500, window[selected])
        assertEquals(470, window.first())
    }

    @Test
    fun `the window stays full at either end`() {
        val (head, headSelected) = galleryWindow(items, 3, max = 60)
        assertEquals(0, head.first())
        assertEquals(60, head.size)
        assertEquals(3, head[headSelected])

        val (tail, tailSelected) = galleryWindow(items, 999, max = 60)
        assertEquals(999, tail.last())
        assertEquals(60, tail.size)
        assertEquals(999, tail[tailSelected])
    }

    @Test
    fun `an out of range selection is clamped`() {
        assertEquals(0, galleryWindow(emptyList<Int>(), 5).second)
        assertEquals(9, galleryWindow(items.take(10), 99).second)
    }

    @Test
    fun `a tile preview asks the cdn for a small webp and keeps the signature`() {
        assertEquals(
            "https://media.discordapp.net/attachments/1/2/a.png?ex=1&is=2&hm=3&format=webp&width=660&height=660",
            mediaTilePreviewUrl("https://cdn.discordapp.com/attachments/1/2/a.png?ex=1&is=2&hm=3", 660),
        )
    }

    @Test
    fun `a tile preview replaces an existing size rather than stacking it`() {
        assertEquals(
            "https://media.discordapp.net/attachments/1/2/v.mp4?ex=1&format=webp&width=660&height=660",
            mediaTilePreviewUrl("https://media.discordapp.net/attachments/1/2/v.mp4?ex=1&format=webp&width=1280&height=720", 660),
        )
    }

    @Test
    fun `urls off the discord cdn are left alone`() {
        val external = "https://images-ext-1.discordapp.net/external/x/https/example.com/a.png"
        assertEquals(external, mediaTilePreviewUrl(external, 660))
    }
}
