package com.discord.widgets.guild_role_subscriptions.tier.create.benefits

import com.discord.api.guildrolesubscription.GuildRoleSubscriptionBenefitType
import com.discord.api.guildrolesubscription.SubscriptionTrialInterval
import com.discord.utilities.recycler.DiffKeyProvider
import com.discord.widgets.guild_role_subscriptions.tier.model.Benefit

sealed class GuildRoleSubscriptionBenefitAdapterItem : DiffKeyProvider {

    data class AddBenefitItem(
        val type: GuildRoleSubscriptionBenefitType,
        val guildId: Long,
        val tierName: String?,
    ) : GuildRoleSubscriptionBenefitAdapterItem() {
        override val key = "AddBenefitItem$type"
    }

    data class BenefitItem(
        val type: GuildRoleSubscriptionBenefitType,
        val benefit: Benefit,
        val guildId: Long,
        val tierName: String?,
    ) : GuildRoleSubscriptionBenefitAdapterItem() {
        override val key = benefit.hashCode().toString()
    }

    data class FreeTrialItem(
        val isFreeTrialEnabled: Boolean,
        val trialInterval: SubscriptionTrialInterval?,
        val activeTrialUserLimit: Int?,
    ) : GuildRoleSubscriptionBenefitAdapterItem() {
        override val key = "FreeTrialItem"
    }

    data class Header(
        val type: GuildRoleSubscriptionBenefitType,
    ) : GuildRoleSubscriptionBenefitAdapterItem() {
        override val key = "BenefitHeader$type"
    }
}
