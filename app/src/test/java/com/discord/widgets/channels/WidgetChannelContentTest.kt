package com.discord.widgets.channels

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetChannelContentTest {
    @Test fun directVideoUrlsExcludeWebPlayers() {
        assertTrue(isDirectVideoUrl("https://cdn.discordapp.com/a/video.mp4?x=1"))
        assertTrue(isDirectVideoUrl("https://media.discordapp.net/a/video.webm"))
        assertFalse(isDirectVideoUrl("https://youtube.com/embed/id"))
        assertFalse(isDirectVideoUrl("https://example.com/watch?id=1"))
    }
}
