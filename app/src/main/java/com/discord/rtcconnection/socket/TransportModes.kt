package com.discord.rtcconnection.socket

internal object TransportModes {
    const val AES256_GCM = "aead_aes256_gcm_rtpsize"
    const val XCHACHA20 = "aead_xchacha20_poly1305_rtpsize"

    fun select(advertised: List<String>, forceFallback: Boolean): String = when {
        forceFallback -> XCHACHA20
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
