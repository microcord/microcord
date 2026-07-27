package com.discord.rtcconnection.enums

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the platform constants and the declaration order these enums are decoded with.
 *
 * The values come straight from the AOSP intent extras, and the *order* is the reference's
 * (recovered from its `$VALUES` array). Both matter: a wrong value silently misreads a broadcast,
 * and a wrong order would break anything that persists or transmits an ordinal.
 */
class AudioBroadcastEnumsTest {

    @Test
    fun `sco audio states match the platform constants`() {
        assertEquals(0, ScoAudioState.Disconnected.value)
        assertEquals(1, ScoAudioState.Connected.value)
        assertEquals(2, ScoAudioState.Connecting.value)
        assertEquals(-1, ScoAudioState.Error.value)
        assertEquals(
            listOf(
                ScoAudioState.Disconnected,
                ScoAudioState.Connected,
                ScoAudioState.Connecting,
                ScoAudioState.Error,
            ),
            ScoAudioState.values().toList(),
        )
        assertEquals(ScoAudioState.Connected, ScoAudioState.from(1))
        assertNull(ScoAudioState.from(7))
    }

    @Test
    fun `bluetooth headset audio states match the platform constants`() {
        assertEquals(10, BluetoothHeadsetAudioState.Disconnected.value)
        assertEquals(11, BluetoothHeadsetAudioState.Connecting.value)
        assertEquals(12, BluetoothHeadsetAudioState.Connected.value)
        assertEquals(
            BluetoothHeadsetAudioState.Connected,
            BluetoothHeadsetAudioState.from(12),
        )
    }

    @Test
    fun `bluetooth profile connection states match the platform constants`() {
        assertEquals(0, BluetoothProfileConnectionState.Disconnected.value)
        assertEquals(1, BluetoothProfileConnectionState.Connecting.value)
        assertEquals(2, BluetoothProfileConnectionState.Connected.value)
        assertEquals(3, BluetoothProfileConnectionState.Disconnecting.value)
    }

    @Test
    fun `broadcast actions match the platform action strings`() {
        assertEquals(
            "android.intent.action.HEADSET_PLUG",
            AudioManagerBroadcastAction.HeadsetPlug.action,
        )
        assertEquals(
            "android.media.ACTION_SCO_AUDIO_STATE_UPDATED",
            AudioManagerBroadcastAction.ScoAudioStateUpdated.action,
        )
        assertEquals(
            AudioManagerBroadcastAction.HeadsetPlug,
            AudioManagerBroadcastAction.from("android.intent.action.HEADSET_PLUG"),
        )
        assertNull(AudioManagerBroadcastAction.from("android.intent.action.NOPE"))

        assertEquals(
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
            BluetoothBroadcastAction.HeadsetConnectionStateChanged.action,
        )
        assertEquals(
            "android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED",
            BluetoothBroadcastAction.HeadsetAudioStateChanged.action,
        )
    }

    @Test
    fun `wired headset state carries the plug flag`() {
        assertEquals(false, WiredHeadsetState.Unplugged.isPlugged)
        assertEquals(true, WiredHeadsetState.Plugged("Headset", true).isPlugged)
    }
}
