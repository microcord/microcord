package com.discord.models.domain

/** A single privacy consent toggle as returned by `users/@me/consent`. */
data class Consent(
    val consented: Boolean = false,
    val changedAt: String? = null,
)

data class Consents(
    val usageStatistics: Consent,
    val personalization: Consent,
) {
    companion object {
        val DEFAULT = Consents(Consent(), Consent())
    }
}
