package com.discord.native.engine

data class AudioInputDeviceDescription(val name: String, val guid: String)

data class AudioOutputDeviceDescription(val name: String, val guid: String)

enum class VideoInputDeviceFacing {
    Unknown,
    Front,
    Back,
}

data class VideoInputDeviceDescription(
    val name: String,
    val guid: String,
    val facing: VideoInputDeviceFacing,
)

data class ConnectionInfo(
    val isConnected: Boolean,
    val protocol: String,
    val localAddress: String,
    val localPort: Int,
)
