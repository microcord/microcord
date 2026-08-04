package com.discord.utilities.mfa

import androidx.annotation.WorkerThread
import com.discord.api.auth.mfa.MfaChallenge

/**
 * Elevates a challenged request, blocking until the user completes or declines verification.
 *
 * The solver owns the `mfa/finish` exchange rather than returning a raw code, because a rejected
 * code (`60008`) has to re-prompt inside the same sheet instead of failing the request it gates.
 *
 * Blocking is safe on an OkHttp thread: connect/read/write timeouts apply to socket operations, not
 * to time spent in an application interceptor, and no call timeout is configured.
 */
interface MfaSolver {

    /** Throws rather than returning null: every non-success outcome carries a reason. */
    @WorkerThread
    @Throws(MfaCancellationException::class)
    fun solve(challenge: MfaChallenge): MfaElevation

    companion object {
        @Volatile
        private var current: MfaSolver? = null

        /** Null uninstalls, leaving the interceptor to hand challenge responses back untouched. */
        @JvmStatic
        fun install(solver: MfaSolver?) {
            current = solver
        }

        @JvmStatic
        fun installed(): MfaSolver? = current
    }
}
