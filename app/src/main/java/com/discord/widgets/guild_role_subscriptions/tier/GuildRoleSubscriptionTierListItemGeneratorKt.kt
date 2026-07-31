@file:JvmName("GuildRoleSubscriptionTierListItemGeneratorKt")

package com.discord.widgets.guild_role_subscriptions.tier

import com.discord.R
import com.discord.widgets.guild_role_subscriptions.tier.model.GuildRoleSubscriptionTier

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
        items += if (guildChannelCount == null) {
            GuildRoleSubscriptionTierAdapterItem.SectionHeader(
                R.string.guild_role_subscription_setup_channel_benefits_channels_title,
                null,
                null,
            )
        } else {
            GuildRoleSubscriptionTierAdapterItem.SectionHeader(
                null,
                R.plurals.guild_role_subscription_tier_review_exclusive_channel_header_count,
                guildChannelCount,
            )
        }
        items += GuildRoleSubscriptionTierAdapterItem.AllChannelsAccessBenefitItem
    } else if (guildRoleSubscriptionTier.channelBenefits.isNotEmpty()) {
        items += GuildRoleSubscriptionTierAdapterItem.SectionHeader(
            null,
            R.plurals.guild_role_subscription_tier_review_exclusive_channel_header_count,
            guildRoleSubscriptionTier.channelBenefits.size,
        )
        guildRoleSubscriptionTier.channelBenefits.forEachIndexed { index, benefit ->
            items += GuildRoleSubscriptionTierAdapterItem.BenefitItem(index, benefit)
        }
    }

    if (guildRoleSubscriptionTier.intangibleBenefits.isNotEmpty()) {
        items += GuildRoleSubscriptionTierAdapterItem.SectionHeader(
            null,
            R.plurals.guild_role_subscription_tier_review_intangible_benefits_header_count,
            guildRoleSubscriptionTier.intangibleBenefits.size,
        )
        guildRoleSubscriptionTier.intangibleBenefits.forEachIndexed { index, benefit ->
            items += GuildRoleSubscriptionTierAdapterItem.BenefitItem(
                guildRoleSubscriptionTier.channelBenefits.size + index,
                benefit,
            )
        }
    }
    return items
}
