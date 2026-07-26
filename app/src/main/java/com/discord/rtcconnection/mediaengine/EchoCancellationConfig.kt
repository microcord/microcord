package com.discord.rtcconnection.mediaengine

/**
 * Device policy for echo cancellation.
 *
 * Android's hardware AEC is unreliable enough that the decision cannot simply be "use it if the
 * platform says it exists": some devices report one and do nothing, others cancel so aggressively
 * that the software canceller on top makes the caller inaudible. These three switches are the
 * experiment surface the client uses to pick a behaviour per device.
 *
 * [isHwAecEnabled] is not configuration — it is the answer the native engine gives back through
 * [com.hammerandchisel.libdiscord.Discord.BuiltinAECCallback], and it is `@Volatile` because that
 * callback arrives on a native thread.
 *
 * Reference: `b.a.q.k0.g` (`EchoCancellation`).
 */
class EchoCancellationConfig(
    /** Ask the native engine for the built-in AEC as soon as the engine is created. */
    val shouldEarlyEnableHwAec: Boolean = true,
    /** Force the software canceller on when the hardware one is not active. */
    val alwaysEnableAec: Boolean = false,
    /** Turn the software canceller off once the hardware one reports itself active. */
    val disableSwAecWhenHwIsEnabled: Boolean = false,
) {
    @Volatile
    var isHwAecEnabled: Boolean = false

    /**
     * Resolves what the software canceller should actually do, given what the settings asked for.
     *
     * Order matters: "hardware is doing it, stand down" wins over "always on", otherwise a device
     * configured with both would double-cancel.
     */
    fun resolveSoftwareAec(requestedBySettings: Boolean): Boolean = when {
        disableSwAecWhenHwIsEnabled && isHwAecEnabled -> false
        alwaysEnableAec && !isHwAecEnabled -> true
        else -> requestedBySettings
    }
}
