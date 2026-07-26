package com.discord.models.domain

import com.discord.utilities.time.TimeUtils

/** A boost the current user has applied to a guild. */
data class ModelAppliedGuildBoost(
    val id: Long,
    val guildId: Long,
    val userId: Long,
)

/** One of the boost slots a Nitro subscription grants, plus its re-assignment cooldown. */
data class ModelGuildBoostSlot(
    val cooldownEndsAt: String?,
    val id: Long,
    val subscriptionId: Long,
    val premiumGuildSubscription: ModelAppliedGuildBoost?,
    val canceled: Boolean,
) {
    val cooldownExpiresAtTimestamp: Long get() = TimeUtils.parseUTCDate(cooldownEndsAt)
}
