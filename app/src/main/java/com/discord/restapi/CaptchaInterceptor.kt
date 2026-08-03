package com.discord.restapi

import com.discord.app.AppLog
import com.discord.utilities.captcha.CaptchaErrorBody
import com.discord.utilities.captcha.CaptchaRequestPolicy
import com.discord.utilities.captcha.CaptchaSolution
import com.discord.utilities.captcha.CaptchaSolver
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Resolves captcha challenges for every request.
 *
 * The API can challenge almost any request, so a challenged response never reaches Retrofit: the
 * challenge is solved and the identical request re-sent, and the caller sees one slow request
 * rather than an error it has to recover from. Being an application interceptor means the retry
 * re-runs the chain below it, picking up fresh auth and analytics headers.
 *
 * The solution travels as headers, which is what makes this generic — the body is never parsed or
 * rebuilt, so multipart uploads work too (`AttachmentRequestBody` reopens its stream per write).
 */
class CaptchaInterceptor(
    private val solverProvider: () -> CaptchaSolver? = { CaptchaSolver.installed() },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val policy = chain.request().tag(CaptchaRequestPolicy::class.java)

        var request = chain.request().withCaptcha(policy?.solution)
        var response = chain.proceed(request)

        if (policy != null && !policy.handleInline) return response

        var challengesShown = 0
        while (true) {
            val challenge = response.captchaChallenge() ?: return response
            val solver = solverProvider() ?: return response
            if (challengesShown >= MAX_CHALLENGES) {
                AppLog.w(TAG, "giving up after $challengesShown unaccepted solutions")
                return response
            }
            challengesShown++

            val solution = try {
                solver.solve(challenge)
            } catch (failure: Throwable) {
                response.closeQuietly()
                throw failure
            }

            response.closeQuietly()
            request = request.withCaptcha(solution)
            response = chain.proceed(request)
        }
    }

    // Peeked, not consumed: the body must survive for the caller when the challenge is unsolvable.
    private fun Response.captchaChallenge(): CaptchaErrorBody? {
        if (code != HTTP_BAD_REQUEST) return null
        val body = runCatching { peekBody(MAX_PEEKED_BODY_BYTES).string() }.getOrNull() ?: return null
        return CaptchaErrorBody.createFromString(body)
    }

    private fun Request.withCaptcha(solution: CaptchaSolution?): Request {
        if (solution == null) return this
        val builder = newBuilder()
        solution.asHeaders().forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private fun Response.closeQuietly() {
        runCatching { close() }
    }

    companion object {
        private const val TAG = "Captcha"
        private const val HTTP_BAD_REQUEST = 400
        private const val MAX_PEEKED_BODY_BYTES = 64L * 1024L

        /**
         * One for the initial demand, one for a solution rejected on bot score. A third would be
         * the server telling us something re-prompting cannot fix.
         */
        const val MAX_CHALLENGES = 2
    }
}
