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

    class UnableToWrapException(cause: Throwable) : Exception(cause)

    companion object {
        @Throws(UnableToWrapException::class)
        fun wrap(raw: PaymentSourceRaw): ModelPaymentSource = try {
            when (raw.type) {
                1 -> ModelPaymentSourceCard(
                    brand = requireNotNull(raw.brand),
                    last4 = requireNotNull(raw.last_4),
                    expiresMonth = raw.expiresMonth,
                    expiresYear = raw.expiresYear,
                    id = raw.id,
                    invalid = raw.invalid,
                    billingAddress = raw.billingAddress,
                    default = raw.default,
                )
                2 -> ModelPaymentSourcePaypal(
                    email = requireNotNull(raw.email),
                    id = raw.id,
                    invalid = raw.invalid,
                    billingAddress = raw.billingAddress,
                    default = raw.default,
                )
                else -> throw IllegalArgumentException("unsupported payment source type: ${raw.type}")
            }
        } catch (failure: Throwable) {
            throw UnableToWrapException(failure)
        }
    }
}
