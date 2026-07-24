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

class VoiceContractTest {
    @Test
    fun defaultsAndStreamKeysMatchReferenceContracts() {
        val voice = StoreMediaSettings.VoiceConfiguration()
        assertEquals(MediaEngineConnection.InputMode.VOICE_ACTIVITY, voice.inputMode)
        assertEquals(-50f, voice.sensitivity)
        assertFalse(voice.isSelfMuted)

        val stream = ModelApplicationStream.GuildStream(1, 2, 3)
        assertEquals(stream, ModelApplicationStream.decodeStreamKey(stream.encodedStreamKey))
        assertTrue(StoreApplicationStreaming.ActiveApplicationStream.State.CONNECTING.isStreamActive())
        assertFalse(StoreApplicationStreaming.ActiveApplicationStream.State.PAUSED.isStreamActive())

        val audio = StoreAudioManagerV2.State()
        assertEquals(DiscordAudioManager.DeviceTypes.INVALID, audio.activeAudioDevice)
        assertEquals(4, audio.audioDevices.size)
    }
}
