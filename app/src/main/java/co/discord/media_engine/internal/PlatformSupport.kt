package co.discord.media_engine.internal

import co.discord.media_engine.CameraEnumeratorProvider
import java.util.Locale

class DeviceInfo {
    fun numberOfDevices() = CameraEnumeratorProvider.get().deviceNames.size
    fun getDeviceName(index: Int): String = CameraEnumeratorProvider.get().deviceNames[index]
    fun getDeviceFrontFacing(index: Int) = CameraEnumeratorProvider.get().isFrontFacing(getDeviceName(index))
    fun getDeviceBackFacing(index: Int) = CameraEnumeratorProvider.get().isBackFacing(getDeviceName(index))
    fun getDeviceGuid(index: Int): String = String.format(
        Locale.US,
        "android_camera_%d_%s_facing",
        index,
        when {
            getDeviceFrontFacing(index) -> "front"
            getDeviceBackFacing(index) -> "back"
            else -> "unknown"
        },
    )
}
