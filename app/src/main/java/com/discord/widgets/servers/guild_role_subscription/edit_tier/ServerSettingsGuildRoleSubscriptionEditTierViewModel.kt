package com.discord.widgets.servers.guild_role_subscription.edit_tier

import com.discord.api.guildrolesubscription.GuildRoleSubscriptionBenefit
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionBenefitType
import com.discord.app.AppViewModel
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.stores.StoreGuildRoleSubscriptions
import com.discord.stores.StoreStream
import com.discord.utilities.error.RestApiError
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.guild_role_subscriptions.GuildRoleSubscriptionUtils
import com.discord.widgets.guild_role_subscriptions.getPrice
import com.discord.widgets.guild_role_subscriptions.tier.model.Benefit
import com.discord.widgets.guild_role_subscriptions.tier.model.GuildRoleSubscriptionTier
import rx.Observable
import rx.subjects.PublishSubject

class ServerSettingsGuildRoleSubscriptionEditTierViewModel(
    private val guildId: Long,
    private val tierListingId: Long,
    private val groupListingId: Long,
    private val store: StoreGuildRoleSubscriptions = StoreStream.getGuildRoleSubscriptions(),
) : AppViewModel<ServerSettingsGuildRoleSubscriptionEditTierViewModel.ViewState>(ViewState.Loading) {
    private val events = PublishSubject.create<Event>()
    private var storedTier: GuildRoleSubscriptionTier? = null
    private var editedTier: GuildRoleSubscriptionTier? = null

    init {
        store.fetchGuildRoleSubscriptionGroupsForGuild(guildId)
        store.observeGuildRoleSubscriptionState(guildId)
            .ui(this)
            .appSubscribe(javaClass, onNext = ::handleStoreState)
    }

    fun observeEvents(): Observable<Event> = events

    fun onTierUpdated(tier: GuildRoleSubscriptionTier) {
        val stored = storedTier ?: return
        editedTier = tier
        updateViewState(
            ViewState.Loaded(
                hasChanges = tier != stored,
                isPublished = stored.isPublished == true,
                tierName = tier.name,
            ),
        )
    }

    fun submitChanges() = mutate(published = null)
    fun publishTier() = mutate(published = true)

    fun deleteTier() {
        updateSubmitting(true)
        GuildRoleSubscriptionUtils.deleteGuildRoleSubscriptionTierListing(
            RestAPI.api,
            store,
            guildId,
            groupListingId,
            tierListingId,
        ).ui(this).appSubscribe(
            javaClass,
            errorHandler = {
                updateSubmitting(false)
                events.onNext(Event.DeleteFailure(it))
            },
            onNext = { events.onNext(Event.DeleteSuccess) },
        )
    }

    private fun mutate(published: Boolean?) {
        val stored = storedTier ?: return
        val edited = editedTier ?: stored
        updateSubmitting(true)
        GuildRoleSubscriptionUtils.updateGuildRoleSubscriptionTierListing(
            restApi = RestAPI.api,
            storeGuildRoleSubscriptions = store,
            guildId = guildId,
            groupListingId = groupListingId,
            tierListingId = tierListingId,
            tierName = edited.name.takeIf { it != stored.name },
            tierDescription = edited.description.takeIf { it != stored.description },
            tierImage = edited.image.takeIf { it != stored.image },
            priceTier = edited.priceTier.takeIf { it != stored.priceTier },
            memberColor = edited.memberColor.takeIf { it != stored.memberColor },
            memberIcon = edited.memberIcon.takeIf { it != stored.memberIcon },
            canAccessAllChannels = edited.canAccessAllChannelsOrDefault(true)
                .takeIf { it != stored.canAccessAllChannelsOrDefault(true) },
            channelBenefits = edited.channelBenefits.takeIf { it != stored.channelBenefits },
            intangibleBenefits = edited.intangibleBenefits.takeIf { it != stored.intangibleBenefits },
            published = published,
        ).ui(this).appSubscribe(
            javaClass,
            errorHandler = {
                updateSubmitting(false)
                events.onNext(Event.SubmitFailure(it))
            },
            onNext = {
                storedTier = edited.copy(isPublished = published ?: edited.isPublished)
                editedTier = storedTier
                updateSubmitting(false)
                events.onNext(Event.SubmitSuccess)
            },
        )
    }

    private fun handleStoreState(state: StoreGuildRoleSubscriptions.GuildRoleSubscriptionGroupState?) {
        val listing = (state as? StoreGuildRoleSubscriptions.GuildRoleSubscriptionGroupState.Loaded)
            ?.guildRoleSubscriptionGroupListing
        if (state is StoreGuildRoleSubscriptions.GuildRoleSubscriptionGroupState.Failed) {
            updateViewState(ViewState.Failed)
            return
        }
        val tier = listing?.subscriptionListings?.firstOrNull { it.id == tierListingId } ?: return
        val role = StoreStream.getGuilds().getRoles()[guildId]?.get(tier.roleId)
        val benefits = tier.roleBenefits.benefits
        val draft = GuildRoleSubscriptionTier(
            name = tier.name,
            applicationId = tier.applicationId,
            priceTier = tier.getPrice(),
            memberColor = role?.color,
            memberIcon = role?.icon ?: role?.unicodeEmoji,
            guildRole = role,
            image = tier.imageAsset?.id?.let {
                IconUtils.getStoreAssetImage(tier.applicationId, it.toString(), 256)
            },
            imageAssetId = tier.imageAsset?.id,
            description = tier.description,
            isPublished = tier.published,
            canAccessAllChannels = listing.fullServerGate,
            channelBenefits = benefits.mapNotNull { it.toChannelBenefit() },
            intangibleBenefits = benefits.mapNotNull { it.toIntangibleBenefit() },
            trialInterval = store.getGuildRoleSubscriptionFreeTrialForListing(guildId, tierListingId)
                ?.activeTrial?.let { com.discord.api.guildrolesubscription.SubscriptionTrialInterval(it.interval, it.intervalCount) },
            activeTrialUserLimit = store.getGuildRoleSubscriptionFreeTrialForListing(guildId, tierListingId)
                ?.maxNumActiveTrialUsers,
        )
        storedTier = draft
        editedTier = draft
        events.onNext(Event.StoredStateUpdate(draft, listing.fullServerGate))
        updateViewState(ViewState.Loaded(false, tier.published, tier.name))
    }

    private fun GuildRoleSubscriptionBenefit.emoji(): Emoji = emojiId?.let { id ->
        ModelEmojiCustom(id, name, null, true, false, 0, null, false, true, true, guildId)
    } ?: ModelEmojiUnicode(listOf(name), emojiName.orEmpty())

    private fun GuildRoleSubscriptionBenefit.toChannelBenefit() =
        takeIf { refType == GuildRoleSubscriptionBenefitType.CHANNEL && refId != null }?.let {
            Benefit.ChannelBenefit(name, emoji(), description, null, checkNotNull(refId))
        }

    private fun GuildRoleSubscriptionBenefit.toIntangibleBenefit() =
        takeIf { refType == GuildRoleSubscriptionBenefitType.INTANGIBLE }?.let {
            Benefit.IntangibleBenefit(name, emoji(), description)
        }

    private fun updateSubmitting(submitting: Boolean) {
        val state = viewState as? ViewState.Loaded ?: return
        updateViewState(state.copy(isSubmitting = submitting))
    }

    sealed class ViewState {
        object Loading : ViewState()
        object Failed : ViewState()
        data class Loaded(
            val hasChanges: Boolean = false,
            val isPublished: Boolean = false,
            val tierName: String? = null,
            val isSubmitting: Boolean = false,
        ) : ViewState()
    }

    sealed class Event {
        data class StoredStateUpdate(val tier: GuildRoleSubscriptionTier, val isFullServerGating: Boolean) : Event()
        object SubmitSuccess : Event()
        data class SubmitFailure(val error: RestApiError) : Event()
        object DeleteSuccess : Event()
        data class DeleteFailure(val error: RestApiError) : Event()
    }
}
