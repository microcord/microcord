package com.discord.models.gifpicker.dto

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelGifTest {

    @Test
    fun `a proxied tenor video becomes its tiny animated webp`() {
        assertEquals(
            listOf("https://media.tenor.com/mzGVZiAt8f4AAAA1/radlcies-man-asleep.webp"),
            ModelGif.previewUrls(
                "https://images-ext-2.discordapp.net/external/sig/https/media.tenor.com/mzGVZiAt8f4AAAPo/radlcies-man-asleep.mp4",
                200,
                138,
            ),
        )
    }

    @Test
    fun `a gif attachment is sized like chat`() {
        assertEquals(
            listOf(
                "https://media.discordapp.net/attachments/1/2/a.gif?ex=1&width=30&height=20&format=webp&animated=true",
                "https://media.discordapp.net/attachments/1/2/a.gif?ex=1&width=30&height=20",
            ),
            ModelGif.previewUrls("https://media.discordapp.net/attachments/1/2/a.gif?ex=1", 100, 67),
        )
    }

    @Test
    fun `other sources load as they are`() {
        assertEquals(listOf("https://media.tenor.com/x/y.gif"), ModelGif.previewUrls("https://media.tenor.com/x/y.gif", 100, 100))
        assertEquals(emptyList<String>(), ModelGif.previewUrls("", 100, 100))
    }
}
