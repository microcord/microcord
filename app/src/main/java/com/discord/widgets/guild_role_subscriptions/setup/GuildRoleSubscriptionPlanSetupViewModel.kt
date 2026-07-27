package com.discord.widgets.guild_role_subscriptions.setup

import androidx.annotation.MainThread
import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreGuildRoleSubscriptions
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.guild_role_subscriptions.GuildRoleSubscriptionUtils
import com.discord.widgets.guild_role_subscriptions.tier.model.GuildRoleSubscriptionTier
import rx.Observable
import rx.subjects.PublishSubject

/**
 * Drives the seven-step wizard that turns monetisation on for a guild.
 *
 * It owns the *plan* fields directly and mirrors the tier from
 * `GuildRoleSubscriptionTierViewModel`, so [computeCanProceedToNextStep] can gate the Next button
 * against whichever step is showing. That gate is applied in [updateViewState] rather than by each
 * mutator, so no caller can forget it.
 *
 * The wizard creates the storefront *and* its first tier in one submit — see
 * [GuildRoleSubscriptionUtils.createGuildRoleSubscriptionGroupListing].
 */
class GuildRoleSubscriptionPlanSetupViewModel(
    private val guildId: Long,
    private val restApi: RestAPIInterface = RestAPI.api,
    private val storeGuildRoleSubscriptions: StoreGuildRoleSubscriptions =
        StoreStream.getGuildRoleSubscriptions(),
) : AppViewModel<GuildRoleSubscriptionPlanSetupViewModel.ViewState>(ViewState()) {

    data class ViewState(
        val currentStep: Int = 0,
        val isSubmitting: Boolean = false,
        val canProceedToNextStep: Boolean = false,
        val coverImage: String? = null,
        val planDescription: String? = null,
        val isFullServerGating: Boolean? = null,
        val guildRoleSubscriptionTier: GuildRoleSubscriptionTier = GuildRoleSubscriptionTier(),
    )

    sealed class Event {
        object SubmitSuccess : Event()
        data class SubmitFailure(val error: RestApiError) : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()

    fun observeEvents(): Observable<Event> = eventSubject

    fun goToNextStep() {
        val viewState = requireViewState()
        updateViewState(viewState.copy(currentStep = viewState.currentStep + 1))
    }

    /** Step -1 is what the fragment reads as "leave the wizard". */
    fun goToPreviousStep() {
        val viewState = requireViewState()
        updateViewState(viewState.copy(currentStep = viewState.currentStep - 1))
    }

    fun onSubscriptionPlanUpdated(coverImage: String?, description: String?, isFullServerGating: Boolean) {
        updateViewState(
            requireViewState().copy(
                coverImage = coverImage,
                planDescription = description,
                isFullServerGating = isFullServerGating,
            ),
        )
    }

    fun onTierUpdated(guildRoleSubscriptionTier: GuildRoleSubscriptionTier) {
        updateViewState(requireViewState().copy(guildRoleSubscriptionTier = guildRoleSubscriptionTier))
    }

    /**
     * Creates the storefront and its first tier.
     *
     * Silently does nothing when the three mandatory fields are not all present. The Done button is
     * already disabled in that case; this is the second gate, because the wizard's steps can be
     * re-entered.
     */
    fun submit() {
        val viewState = requireViewState()
        val tier = viewState.guildRoleSubscriptionTier
        val name = tier.name
        val priceTier = tier.priceTier
        val memberColor = tier.memberColor
        if (name.isNullOrBlank() || priceTier == null || memberColor == null) return

        updateViewState(viewState.copy(isSubmitting = true))
        GuildRoleSubscriptionUtils
            .createGuildRoleSubscriptionGroupListing(
                restApi = restApi,
                storeGuildRoleSubscriptions = storeGuildRoleSubscriptions,
                guildId = guildId,
                coverImage = viewState.coverImage,
                planDescription = viewState.planDescription,
                isFullServerGating = viewState.isFullServerGating,
                tierName = name,
                tierDescription = tier.description,
                priceTier = priceTier,
                tierImage = tier.image,
                // The colour picker yields an ARGB value; role colours are RGB only.
                memberColor = ColorCompat.removeAlphaComponent(memberColor),
                memberBadge = tier.memberIcon,
                canAccessAllChannels = tier.canAccessAllChannelsOrDefault(viewState.isFullServerGating),
                channelBenefits = tier.channelBenefits,
                intangibleBenefits = tier.intangibleBenefits,
            )
            .ui(this)
            .appSubscribe(
                GuildRoleSubscriptionPlanSetupViewModel::class.java,
                errorHandler = { error ->
                    updateViewState(requireViewState().copy(isSubmitting = false))
                    emitEvent(Event.SubmitFailure(error))
                },
            ) {
                updateViewState(requireViewState().copy(isSubmitting = false))
                emitEvent(Event.SubmitSuccess)
            }
    }

    @MainThread
    private fun emitEvent(event: Event) {
        eventSubject.onNext(event)
    }

    /**
     * Whether the Next button is enabled for [viewState]'s current step.
     *
     * The benefit steps have no requirement — a tier may ship with no perks listed — so they fall
     * through to `true`.
     */
    private fun computeCanProceedToNextStep(viewState: ViewState): Boolean {
        val tier = viewState.guildRoleSubscriptionTier
        return when (viewState.currentStep) {
            FORMAT_TYPE_STEP -> true
            DETAILS_STEP -> !viewState.coverImage.isNullOrBlank() && !viewState.planDescription.isNullOrBlank()
            TIER_DETAILS_STEP ->
                !tier.name.isNullOrBlank() && tier.priceTier != null && !tier.image.isNullOrBlank()
            TIER_CHANNEL_BENEFITS_STEP, TIER_INTANGIBLE_BENEFITS_STEP -> true
            TIER_DESIGN_STEP -> tier.memberColor != null
            else -> false
        }
    }

    override fun updateViewState(viewState: ViewState) {
        super.updateViewState(
            viewState.copy(canProceedToNextStep = computeCanProceedToNextStep(viewState)),
        )
    }

    companion object {
        private const val FORMAT_TYPE_STEP = 0
        private const val DETAILS_STEP = 1
        private const val TIER_DETAILS_STEP = 2
        private const val TIER_CHANNEL_BENEFITS_STEP = 3
        private const val TIER_INTANGIBLE_BENEFITS_STEP = 4
        private const val TIER_DESIGN_STEP = 5
    }
}
