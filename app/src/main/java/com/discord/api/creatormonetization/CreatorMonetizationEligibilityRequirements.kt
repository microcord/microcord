package com.discord.api.creatormonetization

import java.util.Date

/**
 * Per-guild answer to "may this server turn on creator monetization yet?".
 *
 * Each boolean is one requirement the server evaluated; [canApply] is the server's own summary.
 */
data class CreatorMonetizationEligibilityRequirements(
    val guildId: Long,
    val latestRequest: CreatorMonetizationEnableRequest?,
    val canApply: Boolean,
    val sufficient: Boolean,
    val age: Boolean,
    val engagementHealthy: Boolean,
    val nsfwProperties: Map<String, Any>,
    val retentionHealthy: Boolean,
    val safeEnvironment: Boolean,
    val size: Boolean,
    val healthScore: HealthScore?,
    val healthScorePending: Boolean,
    val rejection: Rejection?,
    val minimumAgeInDays: Int,
    val minimumSize: Int,
) {
    /** The raw community-health figures behind [engagementHealthy] and [retentionHealthy]. */
    data class HealthScore(
        val avgNonnewParticipators: Int?,
        val avgNonnewCommunicators: Int?,
        val numIntentfulJoiners: Int?,
        val percRetW1Intentful: Double?,
        val guildSize: Int?,
        val communicators: Int?,
        val percRetW1: Double?,
    )

    /** Set when a previous application was rejected; the guild may try again after this date. */
    data class Rejection(
        val canReapplyAt: Date,
    )
}
