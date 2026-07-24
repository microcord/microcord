package co.discord.media_engine

data class VideoInputDeviceDescription(
    val name: String,
    val guid: String,
    val facing: VideoInputDeviceFacing,
)

enum class VideoInputDeviceFacing {
    Unknown,
    Front,
    Back,
}
