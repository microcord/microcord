package com.discord.widgets.servers.guild_role_subscription.edit_tier

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.discord.R
import com.discord.widgets.guild_role_subscriptions.tier.create.WidgetGuildRoleSubscriptionTierDesign
import com.discord.widgets.guild_role_subscriptions.tier.create.WidgetGuildRoleSubscriptionTierDetails
import com.discord.widgets.guild_role_subscriptions.tier.create.benefits.GuildRoleSubscriptionTierBenefitListType
import com.discord.widgets.guild_role_subscriptions.tier.create.benefits.WidgetGuildRoleSubscriptionTierBenefits

internal class ServerSettingsGuildRoleSubscriptionEditTierAdapter(
    fragment: Fragment,
    private val guildId: Long,
) : FragmentStateAdapter(fragment) {
    override fun getItemCount() = 3

    override fun createFragment(position: Int): Fragment = when (position) {
        DETAILS -> WidgetGuildRoleSubscriptionTierDetails.newInstance(true)
        DESIGN -> WidgetGuildRoleSubscriptionTierDesign.newInstance(true)
        BENEFITS -> WidgetGuildRoleSubscriptionTierBenefits.newInstance(
            guildId,
            GuildRoleSubscriptionTierBenefitListType.ALL,
            true,
        )
        else -> error("Unknown edit tier page: $position")
    }

    fun getTabStringResId(position: Int) = when (position) {
        DETAILS -> R.string.guild_settings_role_subscription_tier_edit_details_title
        DESIGN -> R.string.guild_settings_role_subscription_tier_edit_design_title
        BENEFITS -> R.string.guild_settings_role_subscription_tier_edit_benefits_title
        else -> error("Unknown edit tier page: $position")
    }

    fun isDetailsTab(position: Int) = position == DETAILS

    private companion object {
        const val DETAILS = 0
        const val DESIGN = 1
        const val BENEFITS = 2
    }
}
