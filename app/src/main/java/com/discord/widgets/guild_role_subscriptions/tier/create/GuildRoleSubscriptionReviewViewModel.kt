package com.discord.widgets.guild_role_subscriptions.tier.create

import com.discord.app.AppViewModel
import com.discord.stores.StoreChannels
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.widgets.guild_role_subscriptions.tier.GuildRoleSubscriptionTierAdapterItem
import com.discord.widgets.guild_role_subscriptions.tier.generateGuildRoleSubscriptionReviewListItems
import com.discord.widgets.guild_role_subscriptions.tier.model.GuildRoleSubscriptionTier
import rx.Observable

class GuildRoleSubscriptionReviewViewModel(
    guildId: Long,
    storeChannels: StoreChannels = StoreStream.getChannels(),
    observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    storeStateObservable: Observable<StoreState> = observationDeck.connectRx(storeChannels) {
        StoreState(storeChannels.getChannelsForGuild(guildId).size)
    },
) : AppViewModel<GuildRoleSubscriptionReviewViewModel.ViewState>() {

    private var guildChannelCount: Int? = null
    private var guildRoleSubscriptionTier: GuildRoleSubscriptionTier? = null
    private var isFullServerGating: Boolean? = null
    private var planCoverImage: String? = null
    private var planDescription: String? = null

    init {
        storeStateObservable
            .distinctUntilChanged()
            .computationLatest()
            .ui(this)
            .appSubscribe(GuildRoleSubscriptionReviewViewModel::class.java) {
                guildChannelCount = it.guildChannelCount
                updateList()
            }
    }

    fun updatePlanDetails(description: String, coverImage: String) {
        planDescription = description
        planCoverImage = coverImage
        updateList()
    }

    fun updateTier(
        guildRoleSubscriptionTier: GuildRoleSubscriptionTier,
        isFullServerGating: Boolean?,
    ) {
        this.guildRoleSubscriptionTier = guildRoleSubscriptionTier
        this.isFullServerGating = isFullServerGating
        updateList()
    }

    private fun updateList() {
        updateViewState(
            ViewState(
                generateGuildRoleSubscriptionReviewListItems(
                    guildRoleSubscriptionTier,
                    isFullServerGating,
                    guildChannelCount,
                    planDescription,
                    planCoverImage,
                ),
            ),
        )
    }

    data class StoreState(val guildChannelCount: Int)

    data class ViewState(val items: List<GuildRoleSubscriptionTierAdapterItem>)
}
