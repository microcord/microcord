package com.discord.api.premium

import com.discord.api.utcdatetime.UtcDateTime
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** A partner promotion offered to Nitro subscribers. */
data class OutboundPromotion(
    val id: Long,
    val startDate: UtcDateTime,
    val endDate: UtcDateTime,
    val outboundTitle: String,
    val outboundRedemptionModalBody: String,
    val outboundRedemptionPageLink: String?,
    val outboundRedemptionUrlFormat: String?,
    val outboundTermsAndConditions: String,
)

/** An [OutboundPromotion] the current user has already claimed a code for. */
data class ClaimedOutboundPromotion(
    val code: String,
    val userId: Long,
    val claimedAt: UtcDateTime,
    val promotion: OutboundPromotion,
) {
    /** Codes stay redeemable for 30 days after the promotion itself ends. */
    val redeemByDate: UtcDateTime
        get() = UtcDateTime(promotion.endDate.dateTimeMillis + TimeUnit.DAYS.toMillis(REDEMPTION_WINDOW_DAYS))

    /**
     * Where to send the user to redeem [code]: the partner's templated URL when they supplied one,
     * otherwise the generic redemption page.
     */
    val redemptionLink: String
        get() = promotion.outboundRedemptionUrlFormat
            ?.replace(CODE_PLACEHOLDER, URLEncoder.encode(code, "UTF-8"))
            ?: promotion.outboundRedemptionPageLink
            ?: ""

    private companion object {
        private const val REDEMPTION_WINDOW_DAYS = 30L
        private const val CODE_PLACEHOLDER = "{code}"
    }

    override fun toString(): String =
        "ClaimedOutboundPromotion(code=<redacted>, userId=$userId, claimedAt=$claimedAt, promotion=$promotion)"
}
