package com.discord.utilities.captcha

object CaptchaHelper {
    data class CaptchaPayload(
        val captchaKey: String,
        val captchaRqtoken: String? = null,
    ) {
        override fun toString() = "CaptchaPayload(captchaKey=<redacted>, captchaRqtoken=<redacted>)"
    }
}
