package com.discord.utilities.captcha

import androidx.annotation.WorkerThread

/**
 * Runs a captcha challenge to completion, blocking until it is answered.
 *
 * Blocking is safe on an OkHttp thread: connect/read/write timeouts apply to socket operations, not
 * to time spent in an application interceptor, and no call timeout is configured.
 */
interface CaptchaSolver {

    /** Throws rather than returning null: every non-success outcome carries a reason. */
    @WorkerThread
    @Throws(CaptchaCancellationException::class)
    fun solve(challenge: CaptchaErrorBody): CaptchaSolution

    companion object {
        @Volatile
        private var current: CaptchaSolver? = null

        /** Null uninstalls, leaving the interceptor to hand challenge responses back untouched. */
        @JvmStatic
        fun install(solver: CaptchaSolver?) {
            current = solver
        }

        @JvmStatic
        fun installed(): CaptchaSolver? = current
    }
}
