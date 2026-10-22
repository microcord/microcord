package com.discord.utilities.voice

import com.discord.R
import com.discord.rtcconnection.RtcConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEngineServiceControllerTest {
    @Test
    fun mapsEveryRtcStateToItsNotificationString() {
        val states = listOf(
            RtcConnection.State.Disconnected(false) to R.string.connection_status_disconnected,
            RtcConnection.State.AwaitingEndpoint to R.string.connection_status_awaiting_endpoint,
            RtcConnection.State.Authenticating to R.string.connection_status_authenticating,
            RtcConnection.State.Connecting to R.string.connection_status_connecting,
            RtcConnection.State.RtcDisconnected to R.string.connection_status_rtc_disconnected,
            RtcConnection.State.RtcConnecting to R.string.connection_status_rtc_connecting,
            RtcConnection.State.NoRoute to R.string.connection_status_no_route,
        )

        states.forEach { (state, expectedString) ->
            assertEquals(expectedString, notification(state).stateString)
        }
    }

    @Test
    fun connectedNotificationPrioritizesStreamingThenVideoThenVoice() {
        assertEquals(
            R.string.connection_status_stream_self_connected,
            notification(RtcConnection.State.RtcConnected, streaming = true, video = true).stateString,
        )
        assertEquals(
            R.string.connection_status_video_connected,
            notification(RtcConnection.State.RtcConnected, video = true).stateString,
        )
        assertEquals(
            R.string.connection_status_voice_connected,
            notification(RtcConnection.State.RtcConnected).stateString,
        )
    }

    @Test
    fun aReconnectingDropKeepsTheCallServiceRunning() {
        assertFalse(VoiceEngineServiceController.endsService(notification(RtcConnection.State.Disconnected(true), channelId = 7L)))
        assertTrue(VoiceEngineServiceController.endsService(notification(RtcConnection.State.Disconnected(false), channelId = 7L)))
        assertTrue(VoiceEngineServiceController.endsService(notification(RtcConnection.State.Disconnected(true))))
        assertFalse(VoiceEngineServiceController.endsService(notification(RtcConnection.State.RtcConnected, channelId = 7L)))
    }

    private fun notification(
        state: RtcConnection.State,
        streaming: Boolean = false,
        video: Boolean = false,
        channelId: Long = -1L,
    ) = VoiceEngineServiceController.NotificationData(
        rtcConnectionState = state,
        channelName = "",
        isSelfMuted = false,
        isSelfDeafened = false,
        isSelfStreaming = streaming,
        isVideo = video,
        channelId = channelId,
        guildId = null,
        proximityLockEnabled = false,
        canSpeak = false,
    )
}
