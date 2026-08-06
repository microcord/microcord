package com.discord.widgets.guild_role_subscriptions.tier.create.benefits

import com.discord.api.guildrolesubscription.SubscriptionTrialInterval
import com.discord.app.AppViewModel
import com.discord.stores.StoreGuildRoleSubscriptions
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.widgets.guild_role_subscriptions.GuildRoleSubscriptionsFeatureFlag
import com.discord.widgets.guild_role_subscriptions.tier.model.GuildRoleSubscriptionTier
import rx.Observable

class GuildRoleSubscriptionTierBenefitsViewModel(
    private val guildId: Long,
    private val benefitListType: GuildRoleSubscriptionTierBenefitListType,
    private val guildRoleSubscriptionsStore: StoreGuildRoleSubscriptions =
        StoreStream.getGuildRoleSubscriptions(),
    observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    storeStateObservable: Observable<StoreState> = observationDeck.connectRx(
        StoreStream.getGuilds(),
        guildRoleSubscriptionsStore,
    ) {
        StoreState(
            GuildRoleSubscriptionsFeatureFlag.INSTANCE
                .isGuildEligibleForGuildRoleSubscriptionTrials(guildId),
            guildRoleSubscriptionsStore.getGuildRoleSubscriptionFreeTrialActiveUserLimitOptions(),
            guildRoleSubscriptionsStore.getGuildRoleSubscriptionFreeTrialIntervalOptions(),
        )
    },
) : AppViewModel<GuildRoleSubscriptionTierBenefitsViewModel.ViewState>() {

    private var guildRoleSubscriptionTier: GuildRoleSubscriptionTier? = null
    private var isFullServerGating: Boolean? = null
    private var storeState: StoreState? = null

    init {
        storeStateObservable
            .distinctUntilChanged()
            .computationLatest()
            .ui(this)
            .appSubscribe(GuildRoleSubscriptionTierBenefitsViewModel::class.java) {
                storeState = it
                updateList()
            }
    }

    fun updateBenefits(
        guildRoleSubscriptionTier: GuildRoleSubscriptionTier,
        isFullServerGating: Boolean?,
    ) {
        this.guildRoleSubscriptionTier = guildRoleSubscriptionTier
        this.isFullServerGating = isFullServerGating
        updateList()
    }

    private fun updateList() {
        val state = storeState ?: return
        val tier = guildRoleSubscriptionTier ?: return
        val canAccessAllChannels = tier.canAccessAllChannelsOrDefault(isFullServerGating)
        updateViewState(
            ViewState(
                canChangeChannelSettings = isFullServerGating == true &&
                    benefitListType != GuildRoleSubscriptionTierBenefitListType.INTANGIBLE,
                items = generateGuildRoleSubscriptionBenefitListItems(
                    tier.channelBenefits,
                    tier.intangibleBenefits,
                    benefitListType,
                    guildId,
                    tier.name,
                    canAccessAllChannels,
                    benefitListType == GuildRoleSubscriptionTierBenefitListType.ALL &&
                        state.isGuildEligibleForTrials,
                    tier.trialInterval,
                    tier.activeTrialUserLimit,
                ),
                canAccessAllChannels = canAccessAllChannels,
                activeTrialUserLimitOptions = state.activeTrialUserLimitOptions,
                trialIntervalOptions = state.trialIntervalOptions,
            ),
        )
    }

    data class StoreState(
        val isGuildEligibleForTrials: Boolean,
        val activeTrialUserLimitOptions: List<Int?>,
        val trialIntervalOptions: List<SubscriptionTrialInterval>,
    )

    data class ViewState(
        val canChangeChannelSettings: Boolean = false,
        val items: List<GuildRoleSubscriptionBenefitAdapterItem> = emptyList(),
        val canAccessAllChannels: Boolean? = null,
        val activeTrialUserLimitOptions: List<Int?> = emptyList(),
        val trialIntervalOptions: List<SubscriptionTrialInterval> = emptyList(),
    )
}
