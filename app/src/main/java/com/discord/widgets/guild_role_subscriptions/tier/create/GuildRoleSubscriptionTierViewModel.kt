package com.discord.widgets.guild_role_subscriptions.tier.create

import com.discord.api.guildrolesubscription.SubscriptionTrialInterval
import com.discord.app.AppViewModel
import com.discord.widgets.guild_role_subscriptions.tier.model.Benefit
import com.discord.widgets.guild_role_subscriptions.tier.model.GuildRoleSubscriptionTier

class GuildRoleSubscriptionTierViewModel : AppViewModel<GuildRoleSubscriptionTierViewModel.ViewState>(
    ViewState(),
) {

    fun setCanAccessAllChannels(canAccessAllChannels: Boolean) = updateTier {
        copy(canAccessAllChannels = canAccessAllChannels)
    }

    fun setDefaultMemberColor(defaultMemberColor: Int) {
        if (requireViewState().guildRoleSubscriptionTier.memberColor == null) {
            updateMemberColor(defaultMemberColor)
        }
    }

    fun setFullServerGating(isFullServerGating: Boolean) {
        val tier = requireViewState().guildRoleSubscriptionTier
        updateViewState(
            ViewState(
                tier.copy(
                    canAccessAllChannels = tier.canAccessAllChannelsOrDefault(isFullServerGating),
                ),
                isFullServerGating,
            ),
        )
    }

    fun setTier(guildRoleSubscriptionTier: GuildRoleSubscriptionTier) {
        updateViewState(requireViewState().copy(guildRoleSubscriptionTier = guildRoleSubscriptionTier))
    }

    fun updateBenefit(existingBenefit: Benefit?, newBenefit: Benefit?) {
        if (existingBenefit is Benefit.ChannelBenefit || newBenefit is Benefit.ChannelBenefit) {
            updateChannelBenefit(
                existingBenefit as? Benefit.ChannelBenefit,
                newBenefit as? Benefit.ChannelBenefit,
            )
        } else {
            updateIntangibleBenefit(
                existingBenefit as? Benefit.IntangibleBenefit,
                newBenefit as? Benefit.IntangibleBenefit,
            )
        }
    }

    fun updateChannelBenefit(
        existingChannelBenefit: Benefit.ChannelBenefit?,
        newChannelBenefit: Benefit.ChannelBenefit?,
    ) = updateBenefits(
        existingChannelBenefit,
        newChannelBenefit,
        GuildRoleSubscriptionTier::channelBenefits,
    ) { tier, benefits -> tier.copy(channelBenefits = benefits) }

    fun updateIntangibleBenefit(
        existingIntangibleBenefit: Benefit.IntangibleBenefit?,
        newIntangibleBenefit: Benefit.IntangibleBenefit?,
    ) = updateBenefits(
        existingIntangibleBenefit,
        newIntangibleBenefit,
        GuildRoleSubscriptionTier::intangibleBenefits,
    ) { tier, benefits -> tier.copy(intangibleBenefits = benefits) }

    fun updateDescription(description: String) = updateTier { copy(description = description) }

    @JvmOverloads
    fun updateFreeTrialInterval(trialInterval: SubscriptionTrialInterval? = null) = updateTier {
        copy(trialInterval = trialInterval)
    }

    @JvmOverloads
    fun updateFreeTrialLimit(limit: Int? = null) = updateTier { copy(activeTrialUserLimit = limit) }

    @JvmOverloads
    fun updateImage(image: String? = null) = updateTier { copy(image = image) }

    fun updateMemberColor(memberColor: Int?) = updateTier { copy(memberColor = memberColor) }

    @JvmOverloads
    fun updateMemberIcon(memberIcon: String? = null) = updateTier { copy(memberIcon = memberIcon) }

    fun updatePriceTier(priceTier: Int) = updateTier { copy(priceTier = priceTier) }

    fun updateTierName(tierName: String) = updateTier { copy(name = tierName) }

    private inline fun updateTier(transform: GuildRoleSubscriptionTier.() -> GuildRoleSubscriptionTier) {
        val state = requireViewState()
        updateViewState(state.copy(guildRoleSubscriptionTier = state.guildRoleSubscriptionTier.transform()))
    }

    private fun <T : Benefit> updateBenefits(
        existingBenefit: T?,
        newBenefit: T?,
        getBenefits: (GuildRoleSubscriptionTier) -> List<T>,
        setBenefits: (GuildRoleSubscriptionTier, List<T>) -> GuildRoleSubscriptionTier,
    ) {
        if (existingBenefit == null && newBenefit == null) return

        updateTier {
            val benefits = getBenefits(this).toMutableList()
            when {
                existingBenefit == null -> benefits += checkNotNull(newBenefit)
                newBenefit == null -> benefits -= existingBenefit
                else -> {
                    val index = benefits.indexOf(existingBenefit)
                    if (index == -1) return@updateTier this
                    benefits[index] = newBenefit
                }
            }
            setBenefits(this, benefits)
        }
    }

    data class ViewState(
        val guildRoleSubscriptionTier: GuildRoleSubscriptionTier = GuildRoleSubscriptionTier(),
        val isFullServerGating: Boolean? = null,
    )
}
