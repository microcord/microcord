package com.discord.models.domain

import com.discord.models.domain.billing.ModelBillingAddress

sealed class ModelPaymentSource {
    abstract val id: String
    abstract val invalid: Boolean
    abstract val billingAddress: ModelBillingAddress
    abstract val default: Boolean

    data class ModelPaymentSourceCard(
        val brand: String,
        val last4: String,
        val expiresMonth: Int,
        val expiresYear: Int,
        override val id: String,
        override val invalid: Boolean,
        override val billingAddress: ModelBillingAddress,
        override val default: Boolean,
    ) : ModelPaymentSource() {
        override fun toString() = "ModelPaymentSourceCard(brand=$brand, last4=<redacted>, id=<redacted>)"
    }

    data class ModelPaymentSourcePaypal(
        val email: String,
        override val id: String,
        override val invalid: Boolean,
        override val billingAddress: ModelBillingAddress,
        override val default: Boolean,
    ) : ModelPaymentSource() {
        override fun toString() = "ModelPaymentSourcePaypal(email=<redacted>, id=<redacted>)"
    }
}
