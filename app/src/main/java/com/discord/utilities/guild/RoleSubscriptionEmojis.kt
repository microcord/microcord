package com.discord.utilities.guild

import com.discord.api.guildrolesubscription.GuildRoleSubscriptionTierListing
import com.discord.api.role.GuildRole
import com.discord.models.domain.emoji.ModelEmojiGuild

/**
 * Premium emoji: the ones only a server's subscribers can use.
 *
 * There is no flag for this. An emoji is premium because it is restricted to a role that a
 * subscription tier hands out, so the answer depends on the guild's roles and has to be recomputed
 * whenever those change.
 */
object RoleSubscriptionEmojis {

    /** The reference's fixed allowance, independent of boost level. */
    const val MAX_SLOTS = 25

    /** Roles sold by a subscription tier, which are what an emoji is gated behind. */
    fun subscriptionRoleIds(roles: Map<Long, GuildRole>): Set<Long> = roles.values
        .filter { it.tags?.subscriptionListingId != null }
        .map(GuildRole::id)
        .toSet()

    fun isRoleSubscriptionEmoji(emoji: ModelEmojiGuild, subscriptionRoleIds: Set<Long>): Boolean =
        emoji.roles.any(subscriptionRoleIds::contains)

    /** One row of the emoji-access picker. */
    data class AccessTier(val roleId: Long, val name: String, val archived: Boolean)

    /**
     * The tiers an emoji can be gated behind.
     *
     * Built from the guild's roles rather than the storefront's listings: the roles arrive with the
     * guild and are the same ones [isRoleSubscriptionEmoji] classifies by, so the picker can never
     * offer a tier that would produce an emoji this screen then filters out. Listings are an
     * enrichment — they carry the tier's own name and whether it has been retired — and are simply
     * absent until that fetch lands.
     *
     * Archived tiers sort last, as in the reference; they keep their role and their subscribers, so
     * they still have to be offered.
     */
    fun accessTiers(
        roles: Map<Long, GuildRole>,
        listings: List<GuildRoleSubscriptionTierListing>,
    ): List<AccessTier> {
        val listingByRole = listings.associateBy(GuildRoleSubscriptionTierListing::roleId)
        return roles.values
            .filter { it.tags?.subscriptionListingId != null }
            .sortedByDescending(GuildRole::position)
            .map { role ->
                val listing = listingByRole[role.id]
                AccessTier(
                    roleId = role.id,
                    name = listing?.name?.takeIf(String::isNotBlank) ?: role.name,
                    archived = listing?.archived == true,
                )
            }
            .sortedBy(AccessTier::archived)
    }
}
