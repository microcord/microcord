package com.discord.widgets.voice.controls

import com.discord.R
import com.discord.rtcconnection.audio.DiscordAudioManager
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.widgets.voice.model.CameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchoredVoiceControlsViewTest {
    @Test
    fun presentationMatchesVoiceControlState() {
        val active = anchoredVoiceControlsPresentation(
            MediaEngineConnection.InputMode.PUSH_TO_TALK,
            DiscordAudioManager.DeviceTypes.BLUETOOTH_HEADSET,
            isScreensharing = true,
            cameraState = CameraState.CAMERA_ON,
        )
        assertTrue(active.showPtt)
        assertTrue(active.showCamera)
        assertTrue(active.cameraActive)
        // Deliberately inverted, matching the reference. `AnchoredVoiceControlsView` in the
        // decompile announces string `0x7f120520` (`camera_on`, "Turn On Camera") on the
        // CAMERA_ON branch and `0x7f12051f` (`camera_off`) otherwise — the opposite of every
        // sibling control, and reproduced in `anchoredVoiceControlsPresentation`.
        assertEquals(R.string.camera_on, active.cameraDescription)
        assertEquals(R.drawable.ic_audio_output_bluetooth_white_24dp, active.speakerIcon)
        assertTrue(active.speakerActive)
        assertFalse(active.speakerActivated)
        assertEquals(R.drawable.ic_mobile_screenshare_end_24dp, active.screenShareIcon)
        assertEquals(R.string.stop_streaming, active.screenShareDescription)

        val inactive = anchoredVoiceControlsPresentation(
            MediaEngineConnection.InputMode.VOICE_ACTIVITY,
            DiscordAudioManager.DeviceTypes.EARPIECE,
            isScreensharing = false,
            cameraState = CameraState.CAMERA_DISABLED,
        )
        assertFalse(inactive.showPtt)
        assertFalse(inactive.showCamera)
        assertFalse(inactive.cameraActive)
        assertEquals(R.string.camera_off, inactive.cameraDescription)
        assertFalse(inactive.speakerActive)
        assertFalse(inactive.speakerActivated)
        assertEquals(R.drawable.ic_mobile_screenshare_24dp, inactive.screenShareIcon)
        assertEquals(R.string.screenshare_screen, inactive.screenShareDescription)

        val speakerphone = anchoredVoiceControlsPresentation(
            MediaEngineConnection.InputMode.VOICE_ACTIVITY,
            DiscordAudioManager.DeviceTypes.SPEAKERPHONE,
            isScreensharing = false,
            cameraState = CameraState.CAMERA_OFF,
        )
        assertTrue(speakerphone.speakerActive)
        assertTrue(speakerphone.speakerActivated)
    }
}
