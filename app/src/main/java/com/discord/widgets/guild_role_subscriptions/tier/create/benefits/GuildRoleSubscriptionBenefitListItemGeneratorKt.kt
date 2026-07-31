@file:JvmName("GuildRoleSubscriptionBenefitListItemGeneratorKt")

package com.discord.widgets.guild_role_subscriptions.tier.create.benefits

import com.discord.api.guildrolesubscription.GuildRoleSubscriptionBenefitType
import com.discord.api.guildrolesubscription.SubscriptionTrialInterval
import com.discord.widgets.guild_role_subscriptions.tier.model.Benefit

@Suppress("LongParameterList")
fun generateGuildRoleSubscriptionBenefitListItems(
    channelBenefits: List<Benefit.ChannelBenefit>,
    intangibleBenefits: List<Benefit.IntangibleBenefit>,
    benefitListType: GuildRoleSubscriptionTierBenefitListType,
    guildId: Long,
    tierName: String?,
    canAccessAllChannels: Boolean?,
    includeTrials: Boolean,
    trialInterval: SubscriptionTrialInterval?,
    activeTrialUserLimit: Int?,
): List<GuildRoleSubscriptionBenefitAdapterItem> = buildList {
    if (
        canAccessAllChannels != true &&
        (benefitListType == GuildRoleSubscriptionTierBenefitListType.ALL ||
            benefitListType == GuildRoleSubscriptionTierBenefitListType.CHANNEL)
    ) {
        add(GuildRoleSubscriptionBenefitAdapterItem.Header(GuildRoleSubscriptionBenefitType.CHANNEL))
        channelBenefits.forEach {
            add(GuildRoleSubscriptionBenefitAdapterItem.BenefitItem(
                GuildRoleSubscriptionBenefitType.CHANNEL,
                it,
                guildId,
                tierName,
            ))
        }
        add(GuildRoleSubscriptionBenefitAdapterItem.AddBenefitItem(
            GuildRoleSubscriptionBenefitType.CHANNEL,
            guildId,
            tierName,
        ))
    }

    if (
        benefitListType == GuildRoleSubscriptionTierBenefitListType.ALL ||
        benefitListType == GuildRoleSubscriptionTierBenefitListType.INTANGIBLE
    ) {
        add(GuildRoleSubscriptionBenefitAdapterItem.Header(GuildRoleSubscriptionBenefitType.INTANGIBLE))
        intangibleBenefits.forEach {
            add(GuildRoleSubscriptionBenefitAdapterItem.BenefitItem(
                GuildRoleSubscriptionBenefitType.INTANGIBLE,
                it,
                guildId,
                tierName,
            ))
        }
        add(GuildRoleSubscriptionBenefitAdapterItem.AddBenefitItem(
            GuildRoleSubscriptionBenefitType.INTANGIBLE,
            guildId,
            tierName,
        ))
    }

    if (includeTrials) {
        add(GuildRoleSubscriptionBenefitAdapterItem.FreeTrialItem(
            trialInterval != null,
            trialInterval,
            activeTrialUserLimit,
        ))
    }
}
