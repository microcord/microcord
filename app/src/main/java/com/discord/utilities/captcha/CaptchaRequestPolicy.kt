package com.discord.utilities.captcha

/**
 * Per-request opt-out from inline captcha handling, attached as a Retrofit `@Tag`.
 *
 * Only the message send queue uses it: a queued send can run while the app is backgrounded, so it
 * must fail fast instead of parking a queue worker on a modal, then re-submit with [solution].
 */
data class CaptchaRequestPolicy(
    val handleInline: Boolean = true,
    val solution: CaptchaSolution? = null,
) {
    companion object {
        fun preSolved(solution: CaptchaSolution?) =
            CaptchaRequestPolicy(handleInline = false, solution = solution)
    }
}
