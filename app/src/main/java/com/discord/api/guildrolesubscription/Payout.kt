package com.discord.api.guildrolesubscription

/** Where a creator payout sits in the payment pipeline. */
enum class PayoutStatus(val apiValue: Int) {
    UNKNOWN(0),
    OPEN(1),
    PAID(2),
    PENDING(3),
    MANUAL(4),
    CANCELED(5),
    DEFERRED(6),
    DEFERRED_INTERNAL(7),
    PROCESSING(8),
    ERROR(9),
    REJECTED(10),
    RISK_REVIEW(11),
    SUBMITTED(12),
    PENDING_FUNDS(13),
    ;

    companion object {
        /** Resolves a wire value, unrecognised values folding to [UNKNOWN]. */
        fun from(apiValue: Int): PayoutStatus =
            entries.firstOrNull { it != UNKNOWN && it.apiValue == apiValue } ?: UNKNOWN
    }
}

/** A single payout to a creator for one earning period. */
data class Payout(
    val id: Long,
    val userId: Long,
    val periodStart: String,
    val periodEnd: String,
    val payoutDate: String,
    val status: PayoutStatus,
    val amount: Long?,
)
