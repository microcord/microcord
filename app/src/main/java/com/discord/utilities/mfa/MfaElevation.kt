package com.discord.utilities.mfa

import java.io.IOException

/**
 * The short-lived JWT `mfa/finish` returns, replayed as a request header.
 *
 * The server also sets a `__Secure-recent_mfa` cookie that bypasses further prompts for five
 * minutes, so the cookie jar has to persist for that to hold.
 */
data class MfaElevation(val token: String) {

    fun asHeaders(): Map<String, String> = mapOf(HEADER to token)

    override fun toString(): String = "MfaElevation(token=<redacted>)"

    companion object {
        const val HEADER = "X-Discord-MFA-Authorization"
    }
}

/**
 * The user declined verification, or no offered method can be driven here.
 *
 * An [IOException] because OkHttp allows nothing else out of an interceptor. `RestApiError` maps it
 * to `MFA_CANCELLED` and keeps it silent.
 */
class MfaCancellationException(val reason: String) :
    IOException("MFA verification was not completed: $reason") {

    companion object {
        const val REASON_DECLINED = "declined"
        const val REASON_NO_HOST = "no_host"
        const val REASON_TIMED_OUT = "timed_out"

        /** Every method the account offers needs hardware this app cannot drive (passkey-only). */
        const val REASON_UNSUPPORTED = "unsupported"
    }
}
