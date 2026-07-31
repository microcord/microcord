package com.discord.utilities.voice

import com.discord.models.domain.ModelApplicationStream
import com.discord.stores.StoreApplicationStreaming
import com.discord.stores.StoreStreamRtcConnection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenShareManagerTest {
    @Test
    fun `reference preview cadence and stream lifecycle transitions are preserved`() {
        assertTrue(ScreenShareManager.PREVIEW_DELAY_MS == 5_000L)
        assertTrue(ScreenShareManager.PREVIEW_INTERVAL_MS == 300_000L)
        assertTrue(ScreenShareManager.THUMBNAIL_WIDTH_PX == 512)
        assertTrue(ScreenShareManager.THUMBNAIL_HEIGHT_PX == 288)
        assertTrue(ScreenShareManager.JPEG_QUALITY == 92)

        val old = state("guild:1:2:3", connected = true)
        val replacement = state("guild:1:2:4", connected = true)
        val disconnected = old.copy(
            streamRtcConnection = old.streamRtcConnection.copy(isConnected = false, streamKey = null),
        )

        assertTrue(ScreenShareManager.streamKeyChanged(old, replacement))
        assertFalse(ScreenShareManager.streamKeyChanged(old, old))
        assertTrue(ScreenShareManager.streamConnected(null, old))
        assertTrue(ScreenShareManager.streamDisconnected(old, disconnected))
    }

    private fun state(streamKey: String, connected: Boolean) = ScreenShareManager.State(
        activeStream = StoreApplicationStreaming.ActiveApplicationStream(
            StoreApplicationStreaming.ActiveApplicationStream.State.ACTIVE,
            ModelApplicationStream.decodeStreamKey(streamKey),
        ),
        streamRtcConnection = StoreStreamRtcConnection.State(isConnected = connected, streamKey = streamKey),
        meId = 3L,
    )
}
