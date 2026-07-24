package com.discord.models.domain

data class ModelPhoneVerificationToken(val token: String) {
    override fun toString(): String = "ModelPhoneVerificationToken(token=<redacted>)"

    data class Update(val token: String) {
        override fun toString(): String = "Update(token=<redacted>)"
    }
}

data class ModelLocationMetadata(
    val consentRequired: Boolean,
    val countryCode: String?,
)
