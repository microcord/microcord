package com.discord.utilities.captcha

import com.google.gson.annotations.SerializedName

/** `captcha_service` from a challenge body. */
enum class CaptchaService {
    @SerializedName("hcaptcha")
    HCAPTCHA,

    @SerializedName("recaptcha")
    RECAPTCHA,

    @SerializedName("recaptcha_enterprise")
    RECAPTCHA_ENTERPRISE,

    @SerializedName("turnstile")
    TURNSTILE,
    ;

    /** Published site key for providers that use a fixed one. hCaptcha's is dynamic. */
    val defaultSiteKey: String?
        get() = when (this) {
            HCAPTCHA, TURNSTILE -> null
            RECAPTCHA -> RECAPTCHA_SITE_KEY
            RECAPTCHA_ENTERPRISE -> RECAPTCHA_ENTERPRISE_SITE_KEY
        }

    companion object {
        const val RECAPTCHA_SITE_KEY = "6Lef5iQTAAAAAKeIvIY-DeexoO3gj7ryl9rLMEnn"
        const val RECAPTCHA_ENTERPRISE_SITE_KEY = "6LeYqFcqAAAAAD6iZesmNgVulsO4PkpBdr6NVG6M"

        fun fromWireName(name: String?): CaptchaService? = when (name?.lowercase()) {
            "hcaptcha" -> HCAPTCHA
            "recaptcha" -> RECAPTCHA
            "recaptcha_enterprise" -> RECAPTCHA_ENTERPRISE
            "turnstile" -> TURNSTILE
            else -> null
        }
    }
}
