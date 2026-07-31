package com.discord.widgets.servers.guild_role_subscription

import android.content.Context
import android.content.Intent
import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppViewFlipper
import com.discord.app.appViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.widgets.guild_role_subscriptions.setup.WidgetGuildRoleSubscriptionPlanSetup

class WidgetServerSettingsEmptyGuildRoleSubscriptions :
    AppFragment(R.layout.widget_server_settings_empty_guild_role_subscription) {

    private val guildId by lazy { mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, -1L) }
    private val viewModel by appViewModels { ServerSettingsEmptyGuildRoleSubscriptionsViewModel(guildId) }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled(true)
        setActionBarTitle(R.string.guild_role_subscriptions_channel_label)
        view.findViewById<View>(R.id.empty_guild_role_subscription_cta).setOnClickListener {
            WidgetGuildRoleSubscriptionPlanSetup.launch(requireContext(), guildId)
            requireActivity().finish()
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().distinctUntilChanged().bindToComponentLifecycle(this)
            .appSubscribe(WidgetServerSettingsEmptyGuildRoleSubscriptions::class.java, onNext = ::configureUI)
    }

    private fun configureUI(state: ServerSettingsEmptyGuildRoleSubscriptionsViewModel.ViewState) {
        requireView().findViewById<AppViewFlipper>(R.id.server_settings_empty_guild_role_subscriptions_view_flipper)
            .displayedChild = if (state.isLoading) 0 else 1
        state.isMonetizationEnabled?.let { enabled ->
            requireView().findViewById<View>(R.id.empty_guild_role_subscription_cta).setOnClickListener {
                if (enabled) {
                    WidgetGuildRoleSubscriptionPlanSetup.launch(requireContext(), guildId)
                    requireActivity().finish()
                } else {
                    WidgetServerSettingsEnableMonetizationUnavailable.launch(requireContext())
                }
            }
        }
    }

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"

        fun launch(context: Context, guildId: Long) {
            AppScreenRouter.launch(
                context,
                WidgetServerSettingsEmptyGuildRoleSubscriptions::class.java,
                Intent().putExtra(EXTRA_GUILD_ID, guildId),
            )
        }
    }
}
