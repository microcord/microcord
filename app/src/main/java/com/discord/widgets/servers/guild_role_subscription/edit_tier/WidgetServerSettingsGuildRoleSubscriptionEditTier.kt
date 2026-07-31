package com.discord.widgets.servers.guild_role_subscription.edit_tier

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.constraintlayout.widget.Group
import androidx.viewpager2.widget.ViewPager2
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppViewFlipper
import com.discord.app.appActivityViewModels
import com.discord.app.appViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.widgets.guild_role_subscriptions.tier.create.GuildRoleSubscriptionTierViewModel
import com.discord.widgets.settings.SettingsNavigation
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class WidgetServerSettingsGuildRoleSubscriptionEditTier :
    AppFragment(R.layout.widget_server_settings_guild_role_subscription_edit_tier) {
    private val guildId by lazy { mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, -1) }
    private val groupId by lazy { mostRecentIntent.getLongExtra(EXTRA_GROUP_ID, -1) }
    private val tierId by lazy { mostRecentIntent.getLongExtra(EXTRA_TIER_ID, -1) }
    private val viewModel by appViewModels {
        ServerSettingsGuildRoleSubscriptionEditTierViewModel(guildId, tierId, groupId)
    }
    private val tierViewModel by appActivityViewModels { GuildRoleSubscriptionTierViewModel() }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarSubtitle(R.string.guild_settings_role_subscription_tier_edit_subtitle)
        val adapter = ServerSettingsGuildRoleSubscriptionEditTierAdapter(this, guildId)
        val pager = requireNotNull<ViewPager2>(view.findViewById(R.id.guild_role_subscription_edit_tier_view_pager))
        pager.adapter = adapter
        val tabs = requireNotNull<TabLayout>(view.findViewById(R.id.guild_role_subscription_edit_tier_tabs))
        TabLayoutMediator(tabs, pager) { tab, position -> tab.setText(adapter.getTabStringResId(position)) }.attach()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                view.findViewById<View>(R.id.server_settings_guild_role_subscription_edit_tier_delete).visibility =
                    if (adapter.isDetailsTab(tab.position)) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        view.findViewById<FloatingActionButton>(R.id.server_settings_guild_role_subscription_edit_tier_save)
            .setOnClickListener { viewModel.submitChanges() }
        view.findViewById<MaterialButton>(R.id.guild_role_subscription_edit_tier_publish)
            .setOnClickListener { confirmPublish() }
        view.findViewById<MaterialButton>(R.id.server_settings_guild_role_subscription_edit_tier_delete)
            .setOnClickListener { confirmDelete() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().distinctUntilChanged().bindToComponentLifecycle(this)
            .appSubscribe(javaClass, context, onNext = ::render)
        tierViewModel.observeViewState().distinctUntilChanged().bindToComponentLifecycle(this)
            .appSubscribe(javaClass, onNext = { viewModel.onTierUpdated(it.guildRoleSubscriptionTier) })
        viewModel.observeEvents().bindToComponentLifecycle(this)
            .appSubscribe(javaClass, context, onNext = ::handleEvent)
    }

    private fun render(state: ServerSettingsGuildRoleSubscriptionEditTierViewModel.ViewState) {
        val root = view ?: return
        val flipper = root.findViewById<AppViewFlipper>(R.id.server_settings_guild_role_subscription_edit_tier_view_flipper)
        when (state) {
            ServerSettingsGuildRoleSubscriptionEditTierViewModel.ViewState.Loading -> flipper.displayedChild = 0
            ServerSettingsGuildRoleSubscriptionEditTierViewModel.ViewState.Failed -> requireActivity().finish()
            is ServerSettingsGuildRoleSubscriptionEditTierViewModel.ViewState.Loaded -> {
                flipper.displayedChild = 1
                setActionBarTitle(state.tierName)
                root.findViewById<FloatingActionButton>(R.id.server_settings_guild_role_subscription_edit_tier_save).apply {
                    visibility = if (state.hasChanges) View.VISIBLE else View.GONE
                    isEnabled = !state.isSubmitting
                }
                root.findViewById<Group>(R.id.guild_role_subscription_edit_tier_publish_group).visibility =
                    if (state.isPublished) View.GONE else View.VISIBLE
            }
        }
    }

    private fun handleEvent(event: ServerSettingsGuildRoleSubscriptionEditTierViewModel.Event) {
        when (event) {
            is ServerSettingsGuildRoleSubscriptionEditTierViewModel.Event.StoredStateUpdate -> {
                tierViewModel.setTier(event.tier)
                tierViewModel.setFullServerGating(event.isFullServerGating)
            }
            ServerSettingsGuildRoleSubscriptionEditTierViewModel.Event.SubmitSuccess -> hideKeyboard()
            is ServerSettingsGuildRoleSubscriptionEditTierViewModel.Event.SubmitFailure -> event.error.showToasts(context)
            ServerSettingsGuildRoleSubscriptionEditTierViewModel.Event.DeleteSuccess -> requireActivity().finish()
            is ServerSettingsGuildRoleSubscriptionEditTierViewModel.Event.DeleteFailure -> event.error.showToasts(context)
        }
    }

    private fun confirmPublish() = GuildRoleSubscriptionConfirmationDialog.show(
        parentFragmentManager,
        getString(R.string.guild_role_subscription_publish_tier_dialog_title),
        getString(R.string.guild_role_subscription_publish_tier_dialog_description),
        getString(R.string.guild_role_subscription_publish_tier_cta),
        confirmCallback = viewModel::publishTier,
    )

    private fun confirmDelete() = GuildRoleSubscriptionConfirmationDialog.show(
        parentFragmentManager,
        getString(R.string.guild_role_subscription_tier_delete_confirmation_title),
        getString(R.string.guild_role_subscription_tier_delete_confirmation_subtitle),
        getString(R.string.guild_role_subscription_tier_delete_cta),
        true,
        viewModel::deleteTier,
    )

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_GROUP_ID = "INTENT_EXTRA_GUILD_ROLE_SUBSCRIPTION_GROUP_LISTING_ID"
        private const val EXTRA_TIER_ID = "INTENT_EXTRA_GUILD_ROLE_SUBSCRIPTION_TIER_LISTING_ID"

        fun launch(context: Context, guildId: Long, groupListingId: Long, tierListingId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsGuildRoleSubscriptionEditTier::class.java,
                Intent().putExtra(EXTRA_GUILD_ID, guildId)
                    .putExtra(EXTRA_GROUP_ID, groupListingId)
                    .putExtra(EXTRA_TIER_ID, tierListingId),
            )
        }
    }
}
