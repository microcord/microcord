package com.discord.widgets.voice.model

import com.discord.models.domain.ModelApplicationStream
import com.discord.rtcconnection.audio.DiscordAudioManager
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.stores.StoreApplicationStreaming
import com.discord.stores.StoreAudioManagerV2
import com.discord.stores.StoreMediaSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias StreamState = StoreApplicationStreaming.ActiveApplicationStream.State

class VoiceContractTest {
    @Test
    fun defaultsAndStreamKeysMatchReferenceContracts() {
        val voice = StoreMediaSettings.VoiceConfiguration()
        assertEquals(MediaEngineConnection.InputMode.VOICE_ACTIVITY, voice.inputMode)
        assertEquals(-50f, voice.sensitivity, 0f)
        assertFalse(voice.isSelfMuted())

        val stream = ModelApplicationStream.GuildStream(1, 2, 3)
        assertEquals(stream, ModelApplicationStream.decodeStreamKey(stream.encodedStreamKey))

        val audio = StoreAudioManagerV2.State()
        assertEquals(DiscordAudioManager.DeviceTypes.INVALID, audio.activeAudioDevice)
        assertEquals(4, audio.audioDevices.size)
    }

    /**
     * Regression for the inverted `ACTIVE_STATES`.
     *
     * A paused stream is still connected — the streamer minimised the shared window — while `ENDED`
     * and `DENIED_FULL` are terminal. The target previously listed `ENDED` in place of `PAUSED`,
     * which made `isStreamActive()` wrong in both directions: an ended stream reported active and a
     * paused one reported dead, so pausing tore the stream down and ending it never did.
     */
    @Test
    fun pausedStreamIsActiveAndEndedStreamIsNot() {
        assertTrue(StreamState.CONNECTING.isStreamActive())
        assertTrue(StreamState.ACTIVE.isStreamActive())
        assertTrue(StreamState.RECONNECTING.isStreamActive())
        assertTrue(StreamState.PAUSED.isStreamActive())
        assertFalse(StreamState.ENDED.isStreamActive())
        assertFalse(StreamState.DENIED_FULL.isStreamActive())
    }

    /** Ordinals are wire-adjacent through analytics; the reference order is CONNECTING first. */
    @Test
    fun streamStateDeclarationOrderMatchesReference() {
        assertEquals(
            listOf("CONNECTING", "ACTIVE", "RECONNECTING", "ENDED", "PAUSED", "DENIED_FULL"),
            StreamState.entries.map { it.name },
        )
    }
}
