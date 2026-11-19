package com.discord.widgets.chat.input.gifpicker

import com.discord.models.gifpicker.dto.ModelGif
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GifCategoryViewModelTest {

    private val tenor = ModelGif(
        "https://images-ext-1.discordapp.net/external/sig/https/media.tenor.com/mzGVZiAt8f4AAAPo/radlcies-man-asleep.mp4",
        "https://tenor.com/view/radlcies-man-asleep-head-on-fire-gif-26417244",
        640,
        440,
    )
    private val upload = ModelGif(
        "https://media.discordapp.net/attachments/1/2/Cat_Dance.gif?ex=1&hm=2",
        "https://media.discordapp.net/attachments/1/2/Cat_Dance.gif",
        100,
        100,
    )

    @Test
    fun `favorites match every word of the filter against their link names`() {
        assertTrue(GifCategoryViewModel.matchesFilter(tenor, "  "))
        assertTrue(GifCategoryViewModel.matchesFilter(tenor, "Head fire"))
        assertTrue(GifCategoryViewModel.matchesFilter(upload, "cat dance"))
        assertFalse(GifCategoryViewModel.matchesFilter(tenor, "cat"))
        // The host is not part of a GIF's name.
        assertFalse(GifCategoryViewModel.matchesFilter(upload, "discordapp"))
    }
}
