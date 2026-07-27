@file:JvmName("GuildRoleSubscriptionTierListItemGeneratorKt")

package com.discord.widgets.guild_role_subscriptions.tier

import com.discord.R
import com.discord.widgets.guild_role_subscriptions.tier.model.GuildRoleSubscriptionTier

/**
 * Flattens a draft tier into the rows of its preview.
 *
 * Two things are worth knowing about the ordering:
 *  - a full-server plan replaces the whole channel list with a single "entire server" row, because
 *    listing channels individually would be misleading;
 *  - intangible benefits continue the channel benefits' numbering
 *    ([GuildRoleSubscriptionTierAdapterItem.BenefitItem.index] is the list-wide position), so the
 *    two sections never produce colliding diff keys.
 *
 * The plan header only appears when *both* halves of it are present, since a cover image with no
 * blurb (or the reverse) reads as a rendering bug rather than a partial draft.
 */
@Suppress("LongParameterList")
fun generateGuildRoleSubscriptionReviewListItems(
    guildRoleSubscriptionTier: GuildRoleSubscriptionTier?,
    isFullServerGating: Boolean?,
    guildChannelCount: Int? = null,
    planDescription: String? = null,
    planCoverImage: String? = null,
): List<GuildRoleSubscriptionTierAdapterItem> {
    val items = mutableListOf<GuildRoleSubscriptionTierAdapterItem>()

    if (!planDescription.isNullOrBlank() && !planCoverImage.isNullOrBlank()) {
        items += GuildRoleSubscriptionTierAdapterItem.PlanDetails(planDescription, planCoverImage)
    }

    if (guildRoleSubscriptionTier == null) return items

    items += GuildRoleSubscriptionTierAdapterItem.Header(
        name = guildRoleSubscriptionTier.name,
        price = guildRoleSubscriptionTier.priceTier,
        image = guildRoleSubscriptionTier.image,
        description = guildRoleSubscriptionTier.description,
    )
    items += GuildRoleSubscriptionTierAdapterItem.MemberPreview(
        guildRoleSubscriptionTier.memberColor,
        guildRoleSubscriptionTier.memberIcon,
    )

    if (guildRoleSubscriptionTier.canAccessAllChannelsOrDefault(isFullServerGating)) {
        // With no channel count to hand ("every channel in this server") there is nothing to
        // pluralise, so the header falls back to its fixed title.
        items += if (guildChannelCount == null) {
            GuildRoleSubscriptionTierAdapterItem.SectionHeader(
                titleResId = R.string.guild_role_subscription_setup_channel_benefits_channels_title,
                titlePluralResId = null,
                formatArgument = null,
            )
        } else {
            GuildRoleSubscriptionTierAdapterItem.SectionHeader(
                titleResId = null,
                titlePluralResId = R.plurals.guild_role_subscription_tier_review_exclusive_channel_header_count,
                formatArgument = guildChannelCount,
            )
        }
        items += GuildRoleSubscriptionTierAdapterItem.AllChannelsAccessBenefitItem
    } else if (guildRoleSubscriptionTier.channelBenefits.isNotEmpty()) {
        items += GuildRoleSubscriptionTierAdapterItem.SectionHeader(
            titleResId = null,
            titlePluralResId = R.plurals.guild_role_subscription_tier_review_exclusive_channel_header_count,
            formatArgument = guildRoleSubscriptionTier.channelBenefits.size,
        )
        guildRoleSubscriptionTier.channelBenefits.forEachIndexed { index, benefit ->
            items += GuildRoleSubscriptionTierAdapterItem.BenefitItem(index, benefit)
        }
    }

    if (guildRoleSubscriptionTier.intangibleBenefits.isNotEmpty()) {
        items += GuildRoleSubscriptionTierAdapterItem.SectionHeader(
            titleResId = null,
            titlePluralResId = R.plurals.guild_role_subscription_tier_review_intangible_benefits_header_count,
            formatArgument = guildRoleSubscriptionTier.intangibleBenefits.size,
        )
        val channelBenefitCount = guildRoleSubscriptionTier.channelBenefits.size
        guildRoleSubscriptionTier.intangibleBenefits.forEachIndexed { index, benefit ->
            items += GuildRoleSubscriptionTierAdapterItem.BenefitItem(channelBenefitCount + index, benefit)
        }
    }

    return items
}
