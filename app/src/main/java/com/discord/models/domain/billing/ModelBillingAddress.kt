package com.discord.models.domain.billing

data class ModelBillingAddress(
    val name: String,
    val line_1: String,
    val line_2: String,
    val city: String,
    val state: String,
    val country: String,
    val postalCode: String,
) {
    override fun toString() = "ModelBillingAddress(<redacted>)"
}
