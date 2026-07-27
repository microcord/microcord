package com.discord.utilities.captcha

import com.discord.app.AppLog
import com.discord.utilities.error.RestApiError
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.io.Serializable

/**
 * The captcha challenge the API returned instead of performing the request.
 *
 * A 400 whose body carries `captcha_key` means "solve this and retry". The body also names the
 * provider, the site key to run it against, and — for hCaptcha enterprise — an opaque `rqdata`
 * blob plus the `rqtoken` that has to be echoed back with the solved key.
 *
 * [Serializable] because the reference passes this through an `Intent`/`Bundle` extra to the
 * captcha screen, and this reconstruction does the same.
 *
 * Nothing here is a credential the user typed, but [captchaRqtoken] is a single-use server token,
 * so [toString] redacts it rather than letting it reach a log or a crash report.
 *
 * Reference: `com.discord.utilities.captcha.CaptchaErrorBody`.
 */
data class CaptchaErrorBody(
    val captchaKey: List<String>? = null,
    val captchaSitekey: String? = null,
    val captchaService: CaptchaService? = null,
    val captchaRqdata: String? = null,
    val captchaRqtoken: String? = null,
) : Serializable {

    override fun toString(): String = "CaptchaErrorBody(captchaKey=$captchaKey, " +
        "captchaSitekey=$captchaSitekey, captchaService=$captchaService, " +
        "captchaRqdata=<redacted>, captchaRqtoken=<redacted>)"

    companion object {
        private const val serialVersionUID: Long = 1L

        /**
         * Builds the challenge description from a classified REST failure.
         *
         * The reference re-parses the raw response body with a snake-case Gson. This project's
         * [RestApiError] already parsed that body once and kept every captcha field, so the values
         * are read straight off it — same result, without a second parse of a body that has
         * already been consumed by OkHttp.
         *
         * Returns `null` when the failure was not a captcha challenge at all.
         */
        fun createFromError(error: RestApiError): CaptchaErrorBody? {
            if (!error.isCaptchaRequired) return null
            return CaptchaErrorBody(
                captchaKey = error.messages[CaptchaHelper.CAPTCHA_KEY],
                captchaSitekey = error.captchaSitekey,
                captchaService = CaptchaService.fromWireName(error.captchaService),
                captchaRqdata = error.captchaRqdata,
                captchaRqtoken = error.captchaRqtoken,
            )
        }

        /**
         * Reference `CaptchaErrorBody.createFromString`: parses a raw snake-cased error body.
         *
         * Still needed for the paths that only ever see the body text — the Samsung OAuth flow and
         * the gateway's own captcha-gated join responses.
         */
        fun createFromString(errorString: String): CaptchaErrorBody? = runCatching {
            GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create()
                .fromJson(errorString, CaptchaErrorBody::class.java)
        }.onFailure {
            AppLog.e("failed to parse captcha error body", it)
        }.getOrNull()
    }
}
