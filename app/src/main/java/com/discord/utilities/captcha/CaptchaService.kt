package com.discord.utilities.captcha

import com.google.gson.annotations.SerializedName

/**
 * Which captcha provider the API asked the client to solve.
 *
 * Sent as `captcha_service` inside the error body of a request that was answered with a captcha
 * challenge. Reference: `com.discord.utilities.captcha.CaptchaService`.
 */
enum class CaptchaService {
    @SerializedName("hcaptcha")
    HCAPTCHA,

    @SerializedName("recaptcha")
    RECAPTCHA,
    ;

    companion object {
        /** Resolves the wire name, returning `null` for an unknown or absent service. */
        fun fromWireName(name: String?): CaptchaService? = when (name?.lowercase()) {
            "hcaptcha" -> HCAPTCHA
            "recaptcha" -> RECAPTCHA
            else -> null
        }
    }
}
