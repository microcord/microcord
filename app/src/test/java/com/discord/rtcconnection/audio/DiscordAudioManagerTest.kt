package com.discord.rtcconnection.audio

import com.discord.rtcconnection.audio.DiscordAudioManager.AudioDevice
import com.discord.rtcconnection.audio.DiscordAudioManager.DeviceTypes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the two contracts the rest of the voice stack builds on: the device list is addressed by
 * [DeviceTypes.value], and the automatic routing decision is a fixed priority order.
 */
class DiscordAudioManagerTest {

    private fun devices(
        speakerphone: Boolean = false,
        wired: Boolean = false,
        earpiece: Boolean = false,
        bluetooth: Boolean = false,
    ) = listOf(
        AudioDevice(DeviceTypes.SPEAKERPHONE, speakerphone),
        AudioDevice(DeviceTypes.WIRED_HEADSET, wired),
        AudioDevice(DeviceTypes.EARPIECE, earpiece),
        AudioDevice(DeviceTypes.BLUETOOTH_HEADSET, bluetooth),
    )

    @Test
    fun `device values index the device list`() {
        val list = DiscordAudioManager.defaultAudioDevices()
        assertEquals(4, list.size)
        listOf(
            DeviceTypes.SPEAKERPHONE,
            DeviceTypes.WIRED_HEADSET,
            DeviceTypes.EARPIECE,
            DeviceTypes.BLUETOOTH_HEADSET,
        ).forEach { type -> assertEquals(type, list[type.value].type) }
    }

    @Test
    fun `DEFAULT and INVALID are not routable`() {
        assertEquals(false, DeviceTypes.DEFAULT.isRoutable)
        assertEquals(false, DeviceTypes.INVALID.isRoutable)
        assertEquals(true, DeviceTypes.EARPIECE.isRoutable)
    }

    @Test
    fun `a wired headset outranks every other device`() {
        assertEquals(
            DeviceTypes.WIRED_HEADSET,
            DiscordAudioManager.pickDefaultDevice(
                devices(speakerphone = true, wired = true, earpiece = true, bluetooth = true),
            ),
        )
    }

    @Test
    fun `bluetooth outranks the speaker and the earpiece`() {
        assertEquals(
            DeviceTypes.BLUETOOTH_HEADSET,
            DiscordAudioManager.pickDefaultDevice(
                devices(speakerphone = true, earpiece = true, bluetooth = true),
            ),
        )
    }

    @Test
    fun `the speaker outranks the earpiece`() {
        assertEquals(
            DeviceTypes.SPEAKERPHONE,
            DiscordAudioManager.pickDefaultDevice(devices(speakerphone = true, earpiece = true)),
        )
    }

    @Test
    fun `the earpiece is used when it is the only thing available`() {
        assertEquals(
            DeviceTypes.EARPIECE,
            DiscordAudioManager.pickDefaultDevice(devices(earpiece = true)),
        )
    }

    /** A tablet with no telephony and no accessory still has to play the call somewhere. */
    @Test
    fun `nothing available falls back to the speaker`() {
        assertEquals(DeviceTypes.SPEAKERPHONE, DiscordAudioManager.pickDefaultDevice(devices()))
        assertEquals(DeviceTypes.SPEAKERPHONE, DiscordAudioManager.pickDefaultDevice(emptyList()))
    }
}
