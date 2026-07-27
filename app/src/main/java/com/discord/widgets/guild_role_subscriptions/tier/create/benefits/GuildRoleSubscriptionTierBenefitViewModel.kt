package com.discord.widgets.guild_role_subscriptions.tier.create.benefits

import com.discord.api.guildrolesubscription.GuildRoleSubscriptionBenefitType
import com.discord.app.AppViewModel
import com.discord.models.domain.emoji.Emoji
import com.discord.widgets.guild_role_subscriptions.tier.model.Benefit
import rx.Observable
import rx.subjects.PublishSubject

class GuildRoleSubscriptionTierBenefitViewModel(
    private val benefitType: GuildRoleSubscriptionBenefitType,
    val tierName: String?,
    benefit: Benefit? = null,
) : AppViewModel<GuildRoleSubscriptionTierBenefitViewModel.ViewState>(
    ViewState(
        canSubmitResult = benefit != null,
        name = benefit?.name,
        tierName = tierName,
        leadingNameIconResId = (benefit as? Benefit.ChannelBenefit)?.channelIconResId,
        emoji = benefit?.emoji,
        description = benefit?.description,
        channelId = (benefit as? Benefit.ChannelBenefit)?.channelId,
    ),
) {

    private val eventSubject = PublishSubject.create<Event>()

    fun observeEvents(): Observable<Event> = eventSubject

    fun submitResult() {
        val state = requireViewState()
        val name = state.name ?: return
        val emoji = state.emoji ?: return
        val benefit = when (benefitType) {
            GuildRoleSubscriptionBenefitType.CHANNEL -> Benefit.ChannelBenefit(
                name,
                emoji,
                state.description,
                state.leadingNameIconResId,
                state.channelId ?: return,
            )
            GuildRoleSubscriptionBenefitType.INTANGIBLE ->
                Benefit.IntangibleBenefit(name, emoji, state.description)
            GuildRoleSubscriptionBenefitType.UNKNOWN -> return
        }
        eventSubject.onNext(Event.SendResult(benefit))
    }

    fun updateChannel(channelId: Long, channelName: String, channelIconResId: Int?) {
        updateViewState(
            requireViewState().copy(
                name = channelName,
                leadingNameIconResId = channelIconResId,
                channelId = channelId,
            ),
        )
    }

    fun updateDescription(description: String) {
        updateViewState(requireViewState().copy(description = description))
    }

    fun updateEmoji(emoji: Emoji?) {
        updateViewState(requireViewState().copy(emoji = emoji))
    }

    fun updateName(name: String) {
        updateViewState(requireViewState().copy(name = name))
    }

    override fun updateViewState(viewState: ViewState) {
        val canSubmit = !viewState.name.isNullOrBlank() &&
            viewState.emoji != null &&
            (benefitType != GuildRoleSubscriptionBenefitType.CHANNEL || viewState.channelId != null) &&
            benefitType != GuildRoleSubscriptionBenefitType.UNKNOWN
        super.updateViewState(viewState.copy(canSubmitResult = canSubmit))
    }

    sealed class Event {
        data class SendResult(val benefit: Benefit) : Event()
    }

    data class ViewState(
        val canSubmitResult: Boolean = false,
        val name: String? = null,
        val tierName: String? = null,
        val leadingNameIconResId: Int? = null,
        val emoji: Emoji? = null,
        val description: String? = null,
        val channelId: Long? = null,
    )
}
