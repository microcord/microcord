package com.discord.utilities.captcha

import com.discord.app.AppLog
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.Serializable

/**
 * A captcha challenge: the body of a 400 that carried `captcha_key`.
 *
 * [Serializable] because it travels to the challenge sheet in a `Bundle`.
 */
data class CaptchaErrorBody(
    val captchaKey: List<String>? = null,
    val captchaSitekey: String? = null,
    val captchaService: CaptchaService? = null,
    val captchaRqdata: String? = null,
    val captchaRqtoken: String? = null,
    val captchaSessionId: String? = null,
    val shouldServeInvisible: Boolean = false,
    val userFlow: String? = null,
) : Serializable {

    /**
     * Detection is the presence of `captcha_key`, never its contents: on endpoints that did not
     * used to challenge, the array holds a message for old clients rather than provider errors.
     */
    val isChallenge: Boolean
        get() = captchaKey != null

    val effectiveSiteKey: String?
        get() = captchaSitekey ?: captchaService?.defaultSiteKey

    override fun toString(): String = "CaptchaErrorBody(captchaKey=$captchaKey, " +
        "captchaSitekey=$captchaSitekey, captchaService=$captchaService, " +
        "captchaRqdata=<redacted>, captchaRqtoken=<redacted>, " +
        "captchaSessionId=$captchaSessionId, shouldServeInvisible=$shouldServeInvisible, " +
        "userFlow=$userFlow)"

    companion object {
        private const val serialVersionUID: Long = 1L
        private const val CAPTCHA_KEY_FIELD = "captcha_key"

        private val gson: Gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create()

        /** Parses a raw error body, returning null if it is not a challenge or will not parse. */
        fun createFromString(errorString: String): CaptchaErrorBody? {
            if (!errorString.contains(CAPTCHA_KEY_FIELD)) return null
            return runCatching { gson.fromJson(errorString, CaptchaErrorBody::class.java) }
                .onFailure { AppLog.e("failed to parse captcha error body", it) }
                .getOrNull()
                ?.takeIf { it.isChallenge }
        }
    }
}
