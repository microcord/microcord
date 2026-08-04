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
    val promotionalEmailOptIn: PromotionalEmailMetadata? = null,
) {
    /** [preChecked] only applies when [required]; otherwise the box is not shown at all. */
    data class PromotionalEmailMetadata(
        val required: Boolean = false,
        val preChecked: Boolean = false,
    )
}
