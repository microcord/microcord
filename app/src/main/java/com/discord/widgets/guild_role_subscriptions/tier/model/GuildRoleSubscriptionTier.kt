package com.discord.widgets.guild_role_subscriptions.tier.model

import com.discord.api.guildrolesubscription.SubscriptionTrialInterval
import com.discord.api.role.GuildRole
import com.discord.widgets.guild_role_subscriptions.tier.model.Benefit.ChannelBenefit
import com.discord.widgets.guild_role_subscriptions.tier.model.Benefit.IntangibleBenefit

/**
 * The tier the creator is building, accumulated across every step of the setup wizard.
 *
 * Every field is nullable and defaulted because the wizard fills them in one screen at a time; the
 * step gates in `GuildRoleSubscriptionPlanSetupViewModel` and
 * `CreateGuildRoleSubscriptionTierViewModel` are what decide when enough of it is present to move
 * on, and `submit()` is what finally requires the mandatory three (name, price tier, member colour).
 *
 * [canAccessAllChannels] is private on purpose: it is only meaningful together with the plan-level
 * "full server gate" flag, so callers must go through [canAccessAllChannelsOrDefault].
 */
data class GuildRoleSubscriptionTier(
    val name: String? = null,
    val applicationId: Long? = null,
    val priceTier: Int? = null,
    val memberColor: Int? = null,
    val memberIcon: String? = null,
    val guildRole: GuildRole? = null,
    val image: String? = null,
    val imageAssetId: Long? = null,
    val description: String? = null,
    val isPublished: Boolean? = null,
    private val canAccessAllChannels: Boolean? = null,
    val channelBenefits: List<ChannelBenefit> = emptyList(),
    val intangibleBenefits: List<IntangibleBenefit> = emptyList(),
    val trialInterval: SubscriptionTrialInterval? = null,
    val activeTrialUserLimit: Int? = null,
) {

    /**
     * Whether this tier grants the whole server.
     *
     * Only a plan that gates the *entire* server can do so, hence the [isFullServerGating] guard:
     * a per-channel plan always answers `false` no matter what the tier itself carries. Within a
     * full-server plan an unset [canAccessAllChannels] means "yes" — that is the default for a tier
     * created under such a plan.
     */
    fun canAccessAllChannelsOrDefault(isFullServerGating: Boolean?): Boolean =
        isFullServerGating == true && canAccessAllChannels != false
}
