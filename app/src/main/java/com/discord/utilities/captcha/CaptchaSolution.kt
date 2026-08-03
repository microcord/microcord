package com.discord.utilities.captcha

import java.io.IOException

/**
 * A solved captcha, sent back as request headers. The equivalent body fields are deprecated.
 */
data class CaptchaSolution(
    val key: String,
    val rqtoken: String? = null,
    val sessionId: String? = null,
) {

    /** Empty echo values are omitted; the API treats them as a mismatch. */
    fun asHeaders(): Map<String, String> = buildMap {
        put(HEADER_KEY, key)
        rqtoken?.takeIf(String::isNotEmpty)?.let { put(HEADER_RQTOKEN, it) }
        sessionId?.takeIf(String::isNotEmpty)?.let { put(HEADER_SESSION_ID, it) }
    }

    override fun toString() =
        "CaptchaSolution(key=<redacted>, rqtoken=<redacted>, sessionId=<redacted>)"

    companion object {
        const val HEADER_KEY = "X-Captcha-Key"
        const val HEADER_RQTOKEN = "X-Captcha-Rqtoken"
        const val HEADER_SESSION_ID = "X-Captcha-Session-Id"

        fun of(challenge: CaptchaErrorBody, key: String) = CaptchaSolution(
            key = key,
            rqtoken = challenge.captchaRqtoken,
            sessionId = challenge.captchaSessionId,
        )
    }
}

/**
 * The user declined a challenge, so the request it gated will not be sent.
 *
 * An [IOException] because OkHttp allows nothing else out of an interceptor. `RestApiError` maps it
 * to `CAPTCHA_CANCELLED` and keeps it silent.
 */
class CaptchaCancellationException(val reason: String) :
    IOException("Captcha challenge was not completed: $reason") {

    companion object {
        const val REASON_DECLINED = "declined"
        const val REASON_NO_HOST = "no_host"
        const val REASON_TIMED_OUT = "timed_out"
    }
}
