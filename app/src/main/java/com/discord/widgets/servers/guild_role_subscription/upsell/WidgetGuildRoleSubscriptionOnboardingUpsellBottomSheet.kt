package com.discord.widgets.servers.guild_role_subscription.upsell

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.widgets.servers.creator_monetization_eligibility.onboarding.WidgetServerSettingsCreatorMonetizationOnboarding

class WidgetGuildRoleSubscriptionOnboardingUpsellBottomSheet : AppBottomSheet() {
    private val guildId get() = argumentsOrDefault.getLong(EXTRA_GUILD_ID, -1)

    override fun getContentViewResId() = R.layout.widget_guild_role_subscription_onboarding_upsell_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.confirm_button).setOnClickListener {
            WidgetServerSettingsCreatorMonetizationOnboarding.launch(requireContext(), guildId)
            dismiss()
        }
        view.findViewById<View>(R.id.cancel_button).setOnClickListener { dismiss() }
    }

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"

        fun show(fragmentManager: FragmentManager, guildId: Long) {
            WidgetGuildRoleSubscriptionOnboardingUpsellBottomSheet().apply {
                arguments = bundleOf(EXTRA_GUILD_ID to guildId)
            }.show(fragmentManager, WidgetGuildRoleSubscriptionOnboardingUpsellBottomSheet::class.java.simpleName)
        }
    }
}
