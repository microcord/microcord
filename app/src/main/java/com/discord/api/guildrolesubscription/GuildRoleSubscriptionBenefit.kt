package com.discord.api.guildrolesubscription

/** What kind of thing a [GuildRoleSubscriptionBenefit] unlocks. */
enum class GuildRoleSubscriptionBenefitType(val apiValue: Int) {
    UNKNOWN(0),
    CHANNEL(1),
    INTANGIBLE(2),
    ;

    companion object {
        /** Resolves a wire value, unrecognised values folding to [UNKNOWN]. */
        fun from(apiValue: Int): GuildRoleSubscriptionBenefitType = when (apiValue) {
            CHANNEL.apiValue -> CHANNEL
            INTANGIBLE.apiValue -> INTANGIBLE
            else -> UNKNOWN
        }
    }
}

/**
 * One perk listed on a subscription tier.
 *
 * [refId] points at the referenced entity — a channel for [GuildRoleSubscriptionBenefitType.CHANNEL],
 * absent for purely descriptive perks.
 */
data class GuildRoleSubscriptionBenefit(
    val emojiId: Long?,
    val emojiName: String?,
    val name: String,
    val refType: GuildRoleSubscriptionBenefitType,
    val description: String?,
    val refId: Long?,
)
