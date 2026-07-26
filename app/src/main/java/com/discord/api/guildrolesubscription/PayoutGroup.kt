package com.discord.api.guildrolesubscription

/** Whether a payout group is still accruing, has produced a payout, or was cancelled. */
enum class PayoutGroupStatus(val apiValue: Int) {
    UNKNOWN(0),
    OPEN(1),
    PAYOUT_CREATED(2),
    CANCELED(3),
    ;

    companion object {
        /** Resolves a wire value, unrecognised values folding to [UNKNOWN]. */
        fun from(apiValue: Int): PayoutGroupStatus = when (apiValue) {
            OPEN.apiValue -> OPEN
            PAYOUT_CREATED.apiValue -> PAYOUT_CREATED
            CANCELED.apiValue -> CANCELED
            else -> UNKNOWN
        }
    }
}

/** Which revenue stream a payout group aggregates. */
enum class PayoutGroupType(val apiValue: String) {
    UNKNOWN("unknown"),
    MARKETPLACE("marketplace"),
    STAGE_EVENT_SKU("stage_event_sku"),
    SERVER_ROLE_SUBSCRIPTION("server_role_subscription"),
    ;

    companion object {
        /** Resolves a wire value, unrecognised values folding to [UNKNOWN]. */
        fun from(apiValue: String): PayoutGroupType = when (apiValue) {
            MARKETPLACE.apiValue -> MARKETPLACE
            STAGE_EVENT_SKU.apiValue -> STAGE_EVENT_SKU
            SERVER_ROLE_SUBSCRIPTION.apiValue -> SERVER_ROLE_SUBSCRIPTION
            else -> UNKNOWN
        }
    }
}

/**
 * One earning period's worth of revenue for a single application.
 *
 * [payout] is only present once the group has been closed and paid out.
 */
data class PayoutGroup(
    val id: Long,
    val payoutId: Long,
    val userId: Long,
    val applicationId: Long,
    val groupingId: Long,
    val status: PayoutGroupStatus,
    val amount: Long,
    val amountPayable: Long,
    val amountDeducted: Long,
    val paymentsCount: Long,
    val deductionsCount: Long,
    val currency: String,
    val groupingType: PayoutGroupType,
    val periodStartingAt: String,
    val periodEndingAt: String,
    val payout: Payout?,
)
