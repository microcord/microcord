package co.discord.media_engine.internal

import android.content.Context
import android.util.Log
import co.discord.media_engine.CameraEnumeratorProvider
import com.discord.R
import java.io.File
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

class AssetManagement(val ctx: Context) {
    fun ensureKrispModelsCopied(enabled: Boolean) {
        if (!enabled) {
            cleanup()
            return
        }
        try {
            val root = File(ctx.filesDir, "thz")
            val versionDir = File(root, ctx.getString(R.string.krisp_model_version))
            if (versionDir.exists()) return
            cleanup()
            versionDir.mkdirs()
            ctx.assets.list("thz").orEmpty().forEach { name ->
                ctx.assets.open("thz/$name").use { input ->
                    File(versionDir, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed copying krisp model files: ${e.message}: $e")
            cleanup()
        }
    }

    private fun cleanup() {
        try {
            File(ctx.filesDir, "thz").deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Failed removing krisp model files: ${e.message}: $e")
        }
    }

    private companion object {
        const val TAG = "DiscordKrisp"
    }
}
