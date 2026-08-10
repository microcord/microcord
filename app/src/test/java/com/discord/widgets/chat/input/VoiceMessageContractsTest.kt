package com.discord.widgets.chat.input

import com.discord.api.message.MessageFlags
import com.discord.api.message.attachment.MessageAttachment
import com.discord.restapi.RestAPIParams
import com.discord.widgets.chat.list.downsampleVoiceWaveform
import com.google.gson.Gson
import okio.ByteString.Companion.decodeBase64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMessageContractsTest {
    @Test
    fun `regular music attachments are detected without voice flags`() {
        val attachment = MessageAttachment("https://cdn/file", 10, 1, "", "track.mp3", null, null)
        assertTrue(attachment.isAudio)
    }

    @Test
    fun `voice gesture matches RN cancel and lock thresholds`() {
        assertEquals(VoiceGestureState.SENDING, resolveVoiceGestureState(-20f, -20f, -200f, 40f))
        assertEquals(VoiceGestureState.CANCELLING, resolveVoiceGestureState(-201f, -80f, -200f, 40f))
        assertEquals(VoiceGestureState.LOCKING, resolveVoiceGestureState(-20f, -40f, -200f, 40f))
    }

    @Test
    fun `recorder amplitude stays in waveform byte range`() {
        assertEquals(0, voiceAmplitudeSample(0))
        assertEquals(255, voiceAmplitudeSample(32_767))
    }

    @Test
    fun `playback waveform draws only visible six dp bars`() {
        assertEquals(listOf(0, 2, 4, 6), downsampleVoiceWaveform((0..7).toList(), 4))
    }

    @Test
    fun `voice payload keeps RN flag duration and waveform`() {
        val waveform = encodeVoiceWaveform(List(512) { it })
        assertEquals(VoiceMessageRecorder.WAVEFORM_MAX_SAMPLES, waveform.decodeBase64()!!.size)

        val json = Gson().toJson(
            RestAPIParams.Message(
                content = "",
                flags = MessageFlags.IS_VOICE_MESSAGE,
                attachments = listOf(
                    RestAPIParams.Message.Attachment(0, "voice-message.ogg", false, durationSecs = 1.25, waveform = waveform),
                ),
            ),
        )
        assertTrue(json.contains("\"flags\":8192"))
        assertTrue(json.contains("\"duration_secs\":1.25"))
        assertTrue(json.contains("\"waveform\":\"$waveform\""))
    }
}
