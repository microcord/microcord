package com.discord.widgets.chat.list

import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Timeline
import com.discord.api.message.embed.EmbedType
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineMediaViewTest {
    @Test
    fun `listener implements default callbacks and forwards playback state`() {
        var state = Player.STATE_IDLE
        val listener = inlineMediaPlayerListener({ state = it }, {})

        listener.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        listener.onPlaybackStateChanged(Player.STATE_READY)

        assertEquals(Player.STATE_READY, state)
    }

    @Test
    fun `gifv loops while video ends normally`() {
        assertEquals(Player.REPEAT_MODE_ONE, inlineMediaRepeatMode(EmbedType.GIFV))
        assertEquals(Player.REPEAT_MODE_OFF, inlineMediaRepeatMode(EmbedType.VIDEO))
    }
}
