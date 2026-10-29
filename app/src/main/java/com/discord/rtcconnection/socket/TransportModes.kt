package com.discord.rtcconnection.socket

internal object TransportModes {
    const val AES256_GCM = "aead_aes256_gcm_rtpsize"
    const val XCHACHA20 = "aead_xchacha20_poly1305_rtpsize"

    /**
     * The first of the engine's own modes that the server also offers, as the official client picks
     * it. The engine leaves out AES-GCM on CPUs it can't run on (32-bit ARM), so preferring it from
     * the server list alone leaves such phones unable to encrypt or decrypt anything.
     */
    fun select(advertised: List<String>, engineModes: List<String>, forceFallback: Boolean): String = when {
        forceFallback -> XCHACHA20
        engineModes.isNotEmpty() -> engineModes.firstOrNull { it in advertised } ?: XCHACHA20
        AES256_GCM in advertised -> AES256_GCM
        XCHACHA20 in advertised -> XCHACHA20
        else -> advertised.firstOrNull { it.startsWith("aead_") } ?: XCHACHA20
    }
}

internal enum class VoiceCloseRecovery {
    NONE,
    RETRY_WITH_XCHACHA20,
    RETRY_WITH_DAVE,
    ;

    companion object {
        fun from(code: Int?): VoiceCloseRecovery = when (code) {
            4016 -> RETRY_WITH_XCHACHA20
            4017 -> RETRY_WITH_DAVE
            else -> NONE
        }
    }
}
