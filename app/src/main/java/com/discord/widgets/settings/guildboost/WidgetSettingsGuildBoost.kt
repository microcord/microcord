package com.discord.widgets.settings.guildboost

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppViewFlipper
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.views.guildboost.GuildBoostMarketingView
import com.discord.views.guildboost.GuildBoostSubscriptionUpsellView
import com.discord.widgets.guilds.WidgetGuildSelector
import com.discord.widgets.settings.premium.WidgetSettingsPremium
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class WidgetSettingsGuildBoost : AppFragment(R.layout.widget_settings_boost) {
    private lateinit var viewModel: SettingsGuildBoostViewModel
    private lateinit var flipper: AppViewFlipper
    private lateinit var subscriptions: WidgetSettingsGuildBoostSubscriptionAdapter
    private lateinit var samples: SettingsGuildBoostSampleGuildAdapter
    private lateinit var subtext: LinearLayout
    private lateinit var noGuilds: View
    private lateinit var upsell: GuildBoostSubscriptionUpsellView
    private lateinit var marketing: GuildBoostMarketingView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.premium_guild_subscription_title)
        setActionBarSubtitle(R.string.user_settings)
        viewModel = ViewModelProvider(this)[SettingsGuildBoostViewModel::class.java]
        flipper = view.findViewById(R.id.settings_boost_flipper)
        subtext = view.findViewById(R.id.settings_boost_subtext_container)
        noGuilds = view.findViewById(R.id.no_guilds)
        upsell = view.findViewById(R.id.settings_boost_upsell_view)
        marketing = view.findViewById(R.id.settings_boost_marketing_view)
        subscriptions = WidgetSettingsGuildBoostSubscriptionAdapter(view.findViewById(R.id.settings_boost_recycler))
        samples = SettingsGuildBoostSampleGuildAdapter()
        view.findViewById<RecyclerView>(R.id.settings_boost_recycler).adapter = subscriptions
        view.findViewById<RecyclerView>(R.id.settings_boost_sample_guilds).adapter = samples
        view.findViewById<View>(R.id.settings_boost_retry).setOnClickListener { viewModel.retryClicked() }
        WidgetGuildSelector.registerForResult(this) { guildId, _ -> viewModel.handleGuildSearchCallback(guildId) }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).appSubscribe(javaClass, onNext = ::render)
    }

    private fun render(state: SettingsGuildBoostViewModel.ViewState) {
        when (state) {
            SettingsGuildBoostViewModel.ViewState.Loading -> flipper.displayedChild = VIEW_LOADING
            SettingsGuildBoostViewModel.ViewState.Failure -> flipper.displayedChild = VIEW_FAILURE
            is SettingsGuildBoostViewModel.ViewState.Loaded -> {
                flipper.displayedChild = VIEW_LOADED
                subtext.visibility = if (state.guildBoostItems.isEmpty()) View.GONE else View.VISIBLE
                noGuilds.visibility = if (state.sampleGuildItems.isEmpty()) View.VISIBLE else View.GONE
                subscriptions.configure(
                    state.guildBoostItems,
                    { slotId ->
                        viewModel.subscribeClicked(slotId)
                        WidgetGuildSelector.launch(this)
                    },
                    { slot, guildId ->
                        viewModel.transferClicked(slot, guildId)
                        WidgetGuildSelector.launch(this, filterFunction = WidgetGuildSelector.GuildFilterFunction(StoreStream.getGuilds().getGuilds().keys - guildId))
                    },
                    viewModel::cancelClicked,
                    state.canCancelBoosts,
                    state.canUncancelBoosts,
                )
                samples.configure(state.sampleGuildItems) { guildId ->
                    StoreStream.getGuildSelected().dispatchSampleGuildIdSelected(guildId)
                }
                upsell.configure(state.userPremiumTier, state.sampleGuildItems.isNotEmpty())
                marketing.configure(state.userPremiumTier) {
                    AppScreenRouter.launch(requireContext(), WidgetSettingsPremium::class.java)
                }
                handlePendingAction(state.pendingAction)
            }
        }
    }

    private fun handlePendingAction(action: SettingsGuildBoostViewModel.PendingAction?) {
        when (action) {
            is SettingsGuildBoostViewModel.PendingAction.Subscribe -> action.targetGuildId?.let { guildId ->
                confirm(R.string.premium_guild_subscription_subscribe) {
                    RestAPI.api.subscribeToGuild(guildId, RestAPIParams.GuildBoosting(listOf(action.slotId)))
                        .restSubscribeOn().ui(this).appSubscribe(javaClass, onNext = { viewModel.retryClicked() })
                }
            }
            is SettingsGuildBoostViewModel.PendingAction.Transfer -> action.targetGuildId?.let { guildId ->
                confirm(R.string.premium_guild_subscription_transfer_button) {
                    val applied = action.slot.premiumGuildSubscription ?: return@confirm
                    RestAPI.api.unsubscribeToGuild(action.previousGuildId, applied.id)
                        .flatMap { RestAPI.api.subscribeToGuild(guildId, RestAPIParams.GuildBoosting(listOf(action.slot.id))) }
                        .restSubscribeOn().ui(this).appSubscribe(javaClass, onNext = { viewModel.retryClicked() })
                }
            }
            is SettingsGuildBoostViewModel.PendingAction.Cancel -> confirm(R.string.premium_guild_subscription_cancel_title) {
                RestAPI.api.cancelSubscriptionSlot(action.slotId).restSubscribeOn().ui(this)
                    .appSubscribe(javaClass, onNext = { StoreStream.getGuildBoost().updateGuildBoostSlot(it) })
            }
            is SettingsGuildBoostViewModel.PendingAction.Uncancel -> confirm(R.string.premium_guild_subscription_inventory_uncancel_title) {
                RestAPI.api.uncancelSubscriptionSlot(action.slotId).restSubscribeOn().ui(this)
                    .appSubscribe(javaClass, onNext = { StoreStream.getGuildBoost().updateGuildBoostSlot(it) })
            }
            null -> return
        }
        viewModel.consumePendingAction()
    }

    private fun confirm(title: Int, action: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(title).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ -> action() }.show()
    }

    companion object {
        private const val VIEW_LOADING = 0
        private const val VIEW_FAILURE = 1
        private const val VIEW_LOADED = 2
        fun launch(context: Context) = AppScreenRouter.launch(context, WidgetSettingsGuildBoost::class.java)
    }
}
