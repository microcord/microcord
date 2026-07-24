package com.discord.rtcconnection.audio

class DiscordAudioManager private constructor() {
    data class AudioDevice(
        val type: DeviceTypes = DeviceTypes.INVALID,
        val isAvailable: Boolean = false,
        val id: String? = null,
        val name: String? = null,
    )

    enum class DeviceTypes(val value: Int) {
        DEFAULT(-2),
        INVALID(-1),
        SPEAKERPHONE(0),
        WIRED_HEADSET(1),
        EARPIECE(2),
        BLUETOOTH_HEADSET(3),
    }

    companion object {
        fun defaultAudioDevices() = listOf(
            AudioDevice(DeviceTypes.SPEAKERPHONE),
            AudioDevice(DeviceTypes.WIRED_HEADSET),
            AudioDevice(DeviceTypes.EARPIECE),
            AudioDevice(DeviceTypes.BLUETOOTH_HEADSET),
        )
    }
}
