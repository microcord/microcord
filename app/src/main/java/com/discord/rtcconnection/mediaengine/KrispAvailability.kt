package com.discord.rtcconnection.mediaengine

/**
 * Whether Krisp noise cancellation can run on this device.
 *
 * `libkrisp_wrapper.so` ships with the app, but the model it needs does not — see [KrispAssetPack].
 * The reference gates the same surfaces on `MediaEngineStore.isKrispAvailable()`.
 */
object KrispAvailability {
    val isAvailable: Boolean
        get() = KrispAssetPack.isInstalled
}
