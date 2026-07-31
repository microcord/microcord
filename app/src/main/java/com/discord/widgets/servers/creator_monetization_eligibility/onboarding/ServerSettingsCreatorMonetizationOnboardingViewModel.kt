package com.discord.widgets.servers.creator_monetization_eligibility.onboarding

import com.discord.api.creatormonetization.CreatorMonetizationEligibilityRequirements
import com.discord.api.creatormonetization.CreatorMonetizationEnableRequest
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreStream
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject

class ServerSettingsCreatorMonetizationOnboardingViewModel(
    private val guildId: Long,
    private val restApi: RestAPIInterface = RestAPI.api,
    private val requirementsSubject: BehaviorSubject<RequirementsState> = BehaviorSubject.create(),
    storeStateObservable: Observable<StoreState> = observeStores(guildId),
) : AppViewModel<ServerSettingsCreatorMonetizationOnboardingViewModel.ViewState>(ViewState.Loading) {

    sealed class Event {
        data class ShowErrorToast(val error: Throwable) : Event()
    }

    sealed class RequirementsState {
        object Loading : RequirementsState()
        data class Loaded(val requirements: CreatorMonetizationEligibilityRequirements) : RequirementsState()
        data class Error(val error: RestApiError) : RequirementsState()
    }

    data class StoreState(val guild: Guild?, val isGuildOwner: Boolean)

    sealed class ViewState {
        object Loading : ViewState()
        data class Error(val error: RestApiError) : ViewState()
        data class CreateRequest(
            val requirements: CreatorMonetizationEligibilityRequirements,
            val isGuildOwner: Boolean,
            val isSubmitting: Boolean = false,
        ) : ViewState() {
            val isApplicationPending: Boolean
                get() = requirements.latestRequest?.state == CreatorMonetizationEnableRequest.State.OPEN
        }

        data class AcceptTerms(
            val guild: Guild,
            val isGuildOwner: Boolean,
            val latestRequest: CreatorMonetizationEnableRequest,
            val hasAcceptedTerms: Boolean = false,
            val isSubmitting: Boolean = false,
        ) : ViewState()
    }

    private val eventSubject = PublishSubject.create<Event>()

    init {
        Observable.combineLatest(
            storeStateObservable.computationLatest().distinctUntilChanged(),
            requirementsSubject,
        ) { storeState, requirements -> storeState to requirements }
            .ui(this)
            .subscribe { (storeState, requirements) -> handleStoreState(storeState, requirements) }
        refreshRequirements()
    }

    fun listenForEvents(): Observable<Event> = eventSubject

    fun setHasAcceptedTerms(hasAcceptedTerms: Boolean) {
        val state = viewState as? ViewState.AcceptTerms ?: return
        updateViewState(state.copy(hasAcceptedTerms = hasAcceptedTerms))
    }

    fun createRequestToEnableMonetization() {
        val state = viewState as? ViewState.CreateRequest ?: return
        updateViewState(state.copy(isSubmitting = true))
        restApi.createCreatorMonetizationEnableRequest(guildId)
            .restSubscribeOn(retry = false)
            .ui(this)
            .doOnError { eventSubject.onNext(Event.ShowErrorToast(it)) }
            .appSubscribe(
                errorClass = ServerSettingsCreatorMonetizationOnboardingViewModel::class.java,
                errorHandler = {
                    (viewState as? ViewState.CreateRequest)?.let {
                        updateViewState(it.copy(isSubmitting = false))
                    }
                },
            ) { request ->
                (viewState as? ViewState.CreateRequest)?.let {
                    updateViewState(
                        it.copy(
                            requirements = it.requirements.copy(latestRequest = request),
                            isSubmitting = false,
                        ),
                    )
                }
            }
    }

    fun submitTermsAcceptance() {
        val state = viewState as? ViewState.AcceptTerms ?: return
        updateViewState(state.copy(isSubmitting = true))
        restApi.acceptCreatorMonetizationTerms(guildId, state.latestRequest.id)
            .restSubscribeOn(retry = false)
            .ui(this)
            .doOnError { eventSubject.onNext(Event.ShowErrorToast(it)) }
            .appSubscribe(
                errorClass = ServerSettingsCreatorMonetizationOnboardingViewModel::class.java,
                errorHandler = {
                    (viewState as? ViewState.AcceptTerms)?.let {
                        updateViewState(state.copy(hasAcceptedTerms = false, isSubmitting = false))
                    }
                },
            ) { }
    }

    private fun refreshRequirements() {
        if (requirementsSubject.value is RequirementsState.Loading) return
        requirementsSubject.onNext(RequirementsState.Loading)
        restApi.getCreatorMonetizationEligibilityRequirements(guildId)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = ServerSettingsCreatorMonetizationOnboardingViewModel::class.java,
                errorHandler = { error ->
                    error.showErrorToasts = false
                    requirementsSubject.onNext(RequirementsState.Error(error))
                },
            ) { requirementsSubject.onNext(RequirementsState.Loaded(it)) }
    }

    private fun handleStoreState(storeState: StoreState, requirementsState: RequirementsState) {
        val guild = storeState.guild
        if (guild == null || requirementsState is RequirementsState.Loading) {
            updateViewState(ViewState.Loading)
            return
        }
        when (requirementsState) {
            is RequirementsState.Error -> updateViewState(ViewState.Error(requirementsState.error))
            is RequirementsState.Loaded -> {
                val request = requirementsState.requirements.latestRequest
                if (request?.state == CreatorMonetizationEnableRequest.State.APPROVED) {
                    val current = viewState as? ViewState.AcceptTerms
                    updateViewState(
                        current?.copy(guild = guild)
                            ?: ViewState.AcceptTerms(guild, storeState.isGuildOwner, request),
                    )
                } else {
                    updateViewState(
                        ViewState.CreateRequest(requirementsState.requirements, storeState.isGuildOwner),
                    )
                }
            }
            RequirementsState.Loading -> Unit
        }
    }

    companion object {
        private fun observeStores(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getUsers().observeMe(),
        ) { guild, me -> StoreState(guild, guild?.isOwner(me.id) == true) }
    }
}
