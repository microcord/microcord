package com.discord.restapi

import com.discord.app.AppLog
import com.discord.api.auth.mfa.MfaChallenge
import com.discord.utilities.mfa.MfaElevation
import com.discord.utilities.mfa.MfaErrorBody
import com.discord.utilities.mfa.MfaSolver
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Verifies the user for requests the API refuses without multi-factor authentication.
 *
 * A 401 carrying `code 60003` never reaches Retrofit: the user verifies, and the identical request
 * is re-sent carrying the elevation JWT. Being an application interceptor means the retry re-runs
 * the chain below it, picking up fresh auth and analytics headers.
 */
class MfaInterceptor(
    private val solverProvider: () -> MfaSolver? = { MfaSolver.installed() },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        if (request.isFinishMfa()) return response

        var challengesShown = 0
        while (true) {
            val challenge = response.mfaChallenge() ?: return response
            val solver = solverProvider() ?: return response
            if (challengesShown >= MAX_CHALLENGES) {
                AppLog.w(TAG, "giving up after $challengesShown rejected elevations")
                return response
            }
            challengesShown++

            val elevation = try {
                solver.solve(challenge)
            } catch (failure: Throwable) {
                response.closeQuietly()
                throw failure
            }

            response.closeQuietly()
            request = request.withElevation(elevation)
            response = chain.proceed(request)
        }
    }

    // Peeked, not consumed: the body must survive for the caller when the challenge is unanswerable.
    private fun Response.mfaChallenge(): MfaChallenge? {
        if (code != HTTP_UNAUTHORIZED) return null
        val body = runCatching { peekBody(MAX_PEEKED_BODY_BYTES).string() }.getOrNull() ?: return null
        return MfaErrorBody.createFromString(body)?.toChallenge()
    }

    // A 60003 from the exchange that mints the JWT means the ticket was refused; re-prompting would
    // nest another blocked verification inside this one for as long as the server kept saying no.
    private fun Request.isFinishMfa(): Boolean = url.encodedPath.endsWith(FINISH_MFA_PATH)

    private fun Request.withElevation(elevation: MfaElevation): Request {
        val builder = newBuilder()
        elevation.asHeaders().forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private fun Response.closeQuietly() {
        runCatching { close() }
    }

    companion object {
        private const val TAG = "MFA"
        private const val HTTP_UNAUTHORIZED = 401
        private const val MAX_PEEKED_BODY_BYTES = 64L * 1024L
        private const val FINISH_MFA_PATH = "/mfa/finish"

        /**
         * One for the initial demand, one for an elevation the server still refused. A third would
         * be the server telling us something re-prompting cannot fix.
         */
        const val MAX_CHALLENGES = 2
    }
}
