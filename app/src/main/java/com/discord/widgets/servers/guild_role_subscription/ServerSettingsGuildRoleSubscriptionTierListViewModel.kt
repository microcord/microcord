package com.discord.widgets.servers.guild_role_subscription

import com.discord.app.AppViewModel
import com.discord.stores.StoreGuildRoleSubscriptions
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui

class ServerSettingsGuildRoleSubscriptionTierListViewModel(
    private val guildId: Long,
    private val store: StoreGuildRoleSubscriptions = StoreStream.getGuildRoleSubscriptions(),
) : AppViewModel<ServerSettingsGuildRoleSubscriptionTierListViewModel.ViewState>(ViewState.Loading) {

    init {
        store.fetchGuildRoleSubscriptionGroupsForGuild(guildId)
        store.observeGuildRoleSubscriptionState(guildId)
            .ui(this)
            .appSubscribe(javaClass, onNext = ::handleStoreState)
    }

    internal fun handleStoreState(state: StoreGuildRoleSubscriptions.GuildRoleSubscriptionGroupState?) {
        updateViewState(
            when (state) {
                null, StoreGuildRoleSubscriptions.GuildRoleSubscriptionGroupState.Loading -> ViewState.Loading
                is StoreGuildRoleSubscriptions.GuildRoleSubscriptionGroupState.Failed -> ViewState.Failed
                is StoreGuildRoleSubscriptions.GuildRoleSubscriptionGroupState.Loaded -> {
                    val listing = state.guildRoleSubscriptionGroupListing
                        ?: return updateViewState(ViewState.Failed)
                    ViewState.Loaded(
                        generateServerSettingsGuildRoleSubscriptionTierListItems(
                            listing.subscriptionListings,
                            MAX_TIER_ITEMS,
                        ),
                        listing.id,
                    )
                }
            },
        )
    }

    sealed class ViewState {
        object Loading : ViewState()
        object Failed : ViewState()
        data class Loaded(
            val adapterItems: List<ServerSettingsGuildRoleSubscriptionTierAdapterItem>,
            val groupListingId: Long,
        ) : ViewState()
    }

    companion object {
        const val MAX_TIER_ITEMS = 3
    }
}
