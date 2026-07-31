package com.discord.widgets.servers.guild_role_subscription

import android.content.Context
import android.content.Intent
import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.app.AppViewFlipper
import com.discord.app.appActivityViewModels
import com.discord.app.appViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.widgets.guild_role_subscriptions.setup.GuildRoleSubscriptionPlanDetailsViewModel
import com.discord.widgets.guild_role_subscriptions.setup.WidgetGuildRoleSubscriptionPlanDetails
import com.discord.widgets.guild_role_subscriptions.setup.WidgetGuildRoleSubscriptionPlanFormat
import com.google.android.material.floatingactionbutton.FloatingActionButton

class WidgetServerSettingsGuildRoleSubscriptions :
    AppFragment(R.layout.widget_server_settings_guild_role_subscriptions) {

    private val guildId by lazy { mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, -1L) }
    private val viewModel by appViewModels { ServerSettingsGuildRoleSubscriptionViewModel(guildId) }
    private val planDetailsViewModel by appActivityViewModels { GuildRoleSubscriptionPlanDetailsViewModel() }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled(true)
        setActionBarTitle(R.string.guild_role_subscriptions_channel_label)
        view.findViewById<View>(R.id.server_settings_guild_role_subscription_save)
            .setOnClickListener { viewModel.submitChanges() }
        childFragmentManager.beginTransaction()
            .replace(
                R.id.guild_role_subscription_plan_details,
                WidgetGuildRoleSubscriptionPlanDetails.newInstance(true),
            )
            .replace(
                R.id.guild_role_subscription_plan_format,
                WidgetGuildRoleSubscriptionPlanFormat.newInstance(true),
            )
            .commit()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().distinctUntilChanged().bindToComponentLifecycle(this)
            .appSubscribe(WidgetServerSettingsGuildRoleSubscriptions::class.java, onNext = ::configureUI)
        viewModel.observeEvents().bindToComponentLifecycle(this)
            .appSubscribe(WidgetServerSettingsGuildRoleSubscriptions::class.java, onNext = ::handleEvent)
        planDetailsViewModel.observeViewState().distinctUntilChanged().bindToComponentLifecycle(this)
            .appSubscribe(WidgetServerSettingsGuildRoleSubscriptions::class.java) { state ->
                viewModel.onGuildRoleSubscriptionGroupUpdated(
                    state.description,
                    state.coverImage,
                    state.isFullServerGate,
                )
            }
    }

    private fun configureUI(state: ServerSettingsGuildRoleSubscriptionViewModel.ViewState) {
        val flipper = requireView().findViewById<AppViewFlipper>(R.id.server_settings_guild_role_subscription_view_flipper)
        when (state) {
            ServerSettingsGuildRoleSubscriptionViewModel.ViewState.Loading -> flipper.displayedChild = 0
            ServerSettingsGuildRoleSubscriptionViewModel.ViewState.Empty -> {
                WidgetServerSettingsEmptyGuildRoleSubscriptions.launch(requireContext(), guildId)
                requireActivity().finish()
            }
            ServerSettingsGuildRoleSubscriptionViewModel.ViewState.Failed -> {
                AppToast.show(this, R.string.default_failure_to_perform_action_message)
                requireActivity().finish()
            }
            is ServerSettingsGuildRoleSubscriptionViewModel.ViewState.Loaded -> {
                flipper.displayedChild = 1
                requireView().findViewById<FloatingActionButton>(R.id.server_settings_guild_role_subscription_save).apply {
                    visibility = if (state.hasChanges) View.VISIBLE else View.GONE
                    isEnabled = !state.isSubmitting
                }
            }
        }
    }

    private fun handleEvent(event: ServerSettingsGuildRoleSubscriptionViewModel.Event) {
        when (event) {
            ServerSettingsGuildRoleSubscriptionViewModel.Event.SubmitSuccess -> hideKeyboard()
            is ServerSettingsGuildRoleSubscriptionViewModel.Event.SubmitFailure -> {
                AppToast.show(this, R.string.default_failure_to_perform_action_message)
                hideKeyboard()
            }
            is ServerSettingsGuildRoleSubscriptionViewModel.Event.StoreStateUpdate ->
                planDetailsViewModel.setGroupPlanDetails(
                    event.guildRoleSubscriptionGroupListing,
                    event.fullServerGatingOverwrite,
                )
        }
    }

    companion object {
        private const val EXTRA_GUILD_ID = "INTENT_EXTRA_SERVER_SETTINGS_GUILD_ROLE_SUBSCRIPTION_GUILD_ID"

        fun launch(context: Context, guildId: Long) {
            AppScreenRouter.launch(
                context,
                WidgetServerSettingsGuildRoleSubscriptions::class.java,
                Intent().putExtra(EXTRA_GUILD_ID, guildId),
            )
        }
    }
}
