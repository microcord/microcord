package com.discord.utilities.voice

import com.discord.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceEngineNotificationBuilderTest {
    @Test
    fun streamingReplacesMuteAndDeafenWithStopStream() {
        assertEquals(
            listOf(
                action(R.drawable.ic_call_disconnect_24dp, R.string.disconnect_self, DISCONNECT),
                action(R.drawable.ic_mobile_screenshare_end_24dp, R.string.stop_streaming, STOP_STREAM),
            ),
            actions(streaming = true, muted = true, deafened = true),
        )
    }

    @Test
    fun voiceActionsReflectMuteAndDeafenState() {
        assertEquals(
            listOf(
                action(R.drawable.ic_call_disconnect_24dp, R.string.disconnect_self, DISCONNECT),
                action(R.drawable.ic_mic_white_18dp_muted, R.string.unmute, TOGGLE_MUTE),
                action(R.drawable.ic_headset_white_18dp_deafened, R.string.undeafen, TOGGLE_DEAFEN),
            ),
            actions(muted = true, deafened = true),
        )
        assertEquals(
            listOf(
                action(R.drawable.ic_call_disconnect_24dp, R.string.disconnect_self, DISCONNECT),
                action(R.drawable.ic_mic_white_18dp, R.string.mute, TOGGLE_MUTE),
                action(R.drawable.ic_headset_white_18dp, R.string.deafen, TOGGLE_DEAFEN),
            ),
            actions(),
        )
    }

    @Test
    fun suppressedSpeakerCannotToggleMute() {
        assertEquals(
            listOf(
                action(R.drawable.ic_call_disconnect_24dp, R.string.disconnect_self, DISCONNECT),
                action(R.drawable.ic_headset_white_18dp, R.string.deafen, TOGGLE_DEAFEN),
            ),
            actions(canToggleMute = false),
        )
    }

    @Test
    fun notificationSecurityContractStaysPrivateAndImmutable() {
        val source = listOf(
            File("src/main/java/com/discord/utilities/voice/VoiceEngineNotificationBuilder.kt"),
            File("app/src/main/java/com/discord/utilities/voice/VoiceEngineNotificationBuilder.kt"),
        ).first(File::isFile).readText()

        assertTrue(source.contains(".setVisibility(NotificationCompat.VISIBILITY_PRIVATE)"))
        assertEquals(2, source.split("immutablePendingIntentFlag()").size - 1)
        assertFalse(source.contains(".setPublicVersion("))
    }

    private fun actions(
        streaming: Boolean = false,
        muted: Boolean = false,
        deafened: Boolean = false,
        canToggleMute: Boolean = true,
    ) = VoiceEngineNotificationBuilder.notificationActions(
        DISCONNECT,
        STOP_STREAM,
        TOGGLE_MUTE.takeIf { canToggleMute },
        TOGGLE_DEAFEN,
        streaming,
        muted,
        deafened,
    )

    private fun action(icon: Int, label: Int, action: String) =
        VoiceEngineNotificationBuilder.Action(icon, label, action)

    private companion object {
        const val DISCONNECT = "disconnect"
        const val STOP_STREAM = "stop-stream"
        const val TOGGLE_MUTE = "toggle-mute"
        const val TOGGLE_DEAFEN = "toggle-deafen"
    }
}
