package com.discord.widgets.guild_role_subscriptions.tier.create

import androidx.annotation.MainThread
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionGroupListing
import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreGuildRoleSubscriptions
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.widgets.guild_role_subscriptions.GuildRoleSubscriptionUtils
import com.discord.widgets.guild_role_subscriptions.getFullServerGatingOverwrite
import com.discord.widgets.guild_role_subscriptions.tier.model.GuildRoleSubscriptionTier
import rx.Observable
import rx.subjects.PublishSubject

class CreateGuildRoleSubscriptionTierViewModel(
    private val guildId: Long,
    private val groupListingId: Long,
    private val restApi: RestAPIInterface = RestAPI.api,
    private val storeGuildRoleSubscriptions: StoreGuildRoleSubscriptions =
        StoreStream.getGuildRoleSubscriptions(),
    storeGuilds: StoreGuilds = StoreStream.getGuilds(),
    observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    storeStateObservable: Observable<StoreState> = observeStoreState(
        guildId,
        observationDeck,
        storeGuildRoleSubscriptions,
        storeGuilds,
    ),
) : AppViewModel<CreateGuildRoleSubscriptionTierViewModel.ViewState>(ViewState()) {

    data class StoreState(
        val guildRoleSubscriptionGroupListing: GuildRoleSubscriptionGroupListing? = null,
        val fullServerGatingOverwrite: Boolean? = null,
    )

    data class ViewState(
        val currentStep: Int = 0,
        val canProceed: Boolean = false,
        val isSubmitting: Boolean = false,
        val guildRoleSubscriptionTier: GuildRoleSubscriptionTier? = null,
    )

    sealed class Event {
        object SubmitSuccess : Event()
        data class SubmitFailure(val error: RestApiError) : Event()
        data class ServerGatingUpdate(val isFullServerGating: Boolean) : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()
    private var isFullServerGating: Boolean? = null

    init {
        storeStateObservable
            .distinctUntilChanged()
            .computationLatest()
            .ui(this)
            .appSubscribe(CreateGuildRoleSubscriptionTierViewModel::class.java, onNext = ::handleStoreState)
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun goToNextStep() {
        val state = requireViewState()
        updateViewState(state.copy(currentStep = state.currentStep + 1))
    }

    fun goToPreviousStep() {
        val state = requireViewState()
        updateViewState(state.copy(currentStep = state.currentStep - 1))
    }

    fun onTierUpdated(guildRoleSubscriptionTier: GuildRoleSubscriptionTier) {
        updateViewState(requireViewState().copy(guildRoleSubscriptionTier = guildRoleSubscriptionTier))
    }

    fun submit() {
        val tier = requireViewState().guildRoleSubscriptionTier ?: return
        val name = tier.name
        val priceTier = tier.priceTier
        val memberColor = tier.memberColor
        if (name.isNullOrBlank() || priceTier == null || memberColor == null) return

        GuildRoleSubscriptionUtils.createGuildRoleSubscriptionTierListing(
            restApi = restApi,
            storeGuildRoleSubscriptions = storeGuildRoleSubscriptions,
            guildId = guildId,
            groupListingId = groupListingId,
            tierName = name,
            tierDescription = tier.description,
            priceTier = priceTier,
            tierImage = tier.image,
            memberColor = ColorCompat.removeAlphaComponent(memberColor),
            memberIcon = tier.memberIcon,
            canAccessAllChannels = tier.canAccessAllChannelsOrDefault(isFullServerGating),
            channelBenefits = tier.channelBenefits,
            intangibleBenefits = tier.intangibleBenefits,
        )
            .ui(this)
            .appSubscribe(
                CreateGuildRoleSubscriptionTierViewModel::class.java,
                errorHandler = { error ->
                    updateViewState(requireViewState().copy(isSubmitting = false))
                    emitEvent(Event.SubmitFailure(error))
                },
            ) {
                updateViewState(requireViewState().copy(isSubmitting = false))
                emitEvent(Event.SubmitSuccess)
            }
    }

    override fun updateViewState(viewState: ViewState) {
        super.updateViewState(viewState.copy(canProceed = computeCanProceedToNextStep(viewState)))
    }

    private fun computeCanProceedToNextStep(viewState: ViewState): Boolean {
        val tier = viewState.guildRoleSubscriptionTier
        return when (viewState.currentStep) {
            DETAILS_STEP ->
                !tier?.name.isNullOrBlank() &&
                    tier?.priceTier != null &&
                    !tier.description.isNullOrBlank() &&
                    !tier.image.isNullOrBlank()
            CHANNEL_BENEFITS_STEP, INTANGIBLE_BENEFITS_STEP -> true
            DESIGN_STEP -> tier?.memberColor != null
            else -> false
        }
    }

    private fun handleStoreState(storeState: StoreState) {
        val listing = storeState.guildRoleSubscriptionGroupListing ?: return
        val fullServerGating = storeState.fullServerGatingOverwrite ?: listing.fullServerGate
        isFullServerGating = fullServerGating
        emitEvent(Event.ServerGatingUpdate(fullServerGating))
    }

    @MainThread
    private fun emitEvent(event: Event) {
        eventSubject.onNext(event)
    }

    companion object {
        private const val DETAILS_STEP = 0
        private const val CHANNEL_BENEFITS_STEP = 1
        private const val INTANGIBLE_BENEFITS_STEP = 2
        private const val DESIGN_STEP = 3

        private fun observeStoreState(
            guildId: Long,
            observationDeck: ObservationDeck,
            storeGuildRoleSubscriptions: StoreGuildRoleSubscriptions,
            storeGuilds: StoreGuilds,
        ): Observable<StoreState> = observationDeck.connectRx(
            storeGuildRoleSubscriptions,
            updateOnConnect = false,
        ) {
            val listing = (
                storeGuildRoleSubscriptions.getGuildRoleSubscriptionState(guildId)
                    as? StoreGuildRoleSubscriptions.GuildRoleSubscriptionGroupState.Loaded
                )?.guildRoleSubscriptionGroupListing
            StoreState(
                listing,
                listing?.getFullServerGatingOverwrite(storeGuilds.getRoles()[guildId]),
            )
        }
    }
}
