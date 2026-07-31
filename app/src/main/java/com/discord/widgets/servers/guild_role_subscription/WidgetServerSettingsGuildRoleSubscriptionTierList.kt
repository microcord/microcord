package com.discord.widgets.servers.guild_role_subscription

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppViewFlipper
import com.discord.app.appViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.widgets.guild_role_subscriptions.tier.create.WidgetCreateGuildRoleSubscriptionTier
import com.discord.widgets.servers.guild_role_subscription.edit_tier.WidgetServerSettingsGuildRoleSubscriptionEditTier
import com.discord.widgets.settings.SettingsNavigation

class WidgetServerSettingsGuildRoleSubscriptionTierList :
    AppFragment(R.layout.widget_server_settings_guild_role_subscription_tier_list),
    ServerSettingsGuildRoleSubscriptionTierAdapter.ItemClickListener {

    private val guildId by lazy { mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, -1) }
    private val viewModel by appViewModels { ServerSettingsGuildRoleSubscriptionTierListViewModel(guildId) }
    private val adapter = ServerSettingsGuildRoleSubscriptionTierAdapter(this)
    private var groupListingId = -1L

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.guild_settings_role_subscription_tiers_tab_title)
        setActionBarSubtitle(R.string.guild_settings_role_subscriptions_title)
        requireNotNull<RecyclerView>(view.findViewById(R.id.server_settings_guild_role_subscription_tier_list)).adapter = adapter
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .distinctUntilChanged()
            .bindToComponentLifecycle(this)
            .appSubscribe(javaClass, context, onNext = ::render)
    }

    private fun render(state: ServerSettingsGuildRoleSubscriptionTierListViewModel.ViewState) {
        val flipper = requireNotNull<AppViewFlipper>(
            view?.findViewById(R.id.server_settings_guild_role_subscription_tier_list_view_flipper),
        )
        when (state) {
            ServerSettingsGuildRoleSubscriptionTierListViewModel.ViewState.Loading -> flipper.displayedChild = 0
            ServerSettingsGuildRoleSubscriptionTierListViewModel.ViewState.Failed -> {
                flipper.displayedChild = 0
                requireActivity().finish()
            }
            is ServerSettingsGuildRoleSubscriptionTierListViewModel.ViewState.Loaded -> {
                groupListingId = state.groupListingId
                adapter.setItems(state.adapterItems)
                flipper.displayedChild = 1
            }
        }
    }

    override fun onAddTierItemClick() {
        if (groupListingId != -1L) {
            WidgetCreateGuildRoleSubscriptionTier.launch(requireContext(), guildId, groupListingId)
        }
    }

    override fun onTierItemClick(tierListingId: Long) {
        if (groupListingId != -1L) {
            WidgetServerSettingsGuildRoleSubscriptionEditTier.launch(
                requireContext(), guildId, groupListingId, tierListingId,
            )
        }
    }

    companion object {
        const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"

        fun launch(context: Context, guildId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsGuildRoleSubscriptionTierList::class.java,
                Intent().putExtra(EXTRA_GUILD_ID, guildId),
            )
        }
    }
}
