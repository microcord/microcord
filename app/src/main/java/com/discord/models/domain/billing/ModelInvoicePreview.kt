package com.discord.models.domain.billing

/** A discount applied to one line of an upcoming invoice. */
data class ModelInvoiceDiscount(
    val amount: Int,
    val type: Int,
)

/**
 * One line of an upcoming invoice.
 *
 * [proration] marks the credit/charge produced by changing plan mid-period, which the UI labels
 * differently from a regular line.
 */
data class ModelInvoiceItem(
    val amount: Int,
    val discount: List<ModelInvoiceDiscount>,
    val id: Long,
    val proration: Boolean,
    val quantity: Int,
    val subscriptionPlanId: Long,
    val subscriptionPlanPrice: Int,
)

/** What the user will be charged if they confirm a subscription change. */
data class ModelInvoicePreview(
    val currency: String,
    val id: Long,
    val invoiceItems: List<ModelInvoiceItem>,
    val subscriptionPeriodEnd: String,
    val subscriptionPeriodStart: String,
    val subtotal: Int,
    val tax: Int,
    val taxInclusive: Boolean,
    val total: Int,
)
