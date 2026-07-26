package com.discord.models.domain

import com.discord.models.domain.billing.ModelBillingAddress

/**
 * Wire shape of a saved payment source before it is projected onto [ModelPaymentSource].
 *
 * Card and PayPal sources share this envelope: cards fill [brand]/[last_4]/[expiresMonth]/
 * [expiresYear] and PayPal fills [email].
 */
data class PaymentSourceRaw(
    val type: Int,
    val id: String,
    val invalid: Boolean,
    val billingAddress: ModelBillingAddress,
    val default: Boolean,
    val email: String?,
    val brand: String?,
    val last_4: String?,
    val expiresMonth: Int,
    val expiresYear: Int,
) {
    override fun toString(): String = "PaymentSourceRaw(type=$type, id=$id, invalid=$invalid, " +
        "billingAddress=$billingAddress, default=$default, email=<redacted>, brand=$brand, " +
        "last_4=<redacted>, expiresMonth=$expiresMonth, expiresYear=$expiresYear)"
}

/** Request body for editing a saved payment source. */
data class PatchPaymentSourceRaw(
    val billingAddress: ModelBillingAddress,
    val default: Boolean,
)
