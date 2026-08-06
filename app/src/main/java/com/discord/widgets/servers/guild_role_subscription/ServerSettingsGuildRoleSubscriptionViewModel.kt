package com.discord.widgets.servers.guild_role_subscription

import com.discord.api.guildrolesubscription.GuildRoleSubscriptionGroupListing
import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreGuildRoleSubscriptions
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.guild_role_subscriptions.GuildRoleSubscriptionUtils
import com.discord.widgets.guild_role_subscriptions.getFullServerGatingOverwrite
import rx.Observable
import rx.subjects.PublishSubject

class ServerSettingsGuildRoleSubscriptionViewModel(
    private val guildId: Long,
    private val restApi: RestAPIInterface = RestAPI.api,
    private val storeGuildRoleSubscriptions: StoreGuildRoleSubscriptions = StoreStream.getGuildRoleSubscriptions(),
    storeGuilds: StoreGuilds = StoreStream.getGuilds(),
    observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    storeObservable: Observable<StoreState> = observeStoreState(
        guildId,
        observationDeck,
        storeGuildRoleSubscriptions,
        storeGuilds,
    ),
) : AppViewModel<ServerSettingsGuildRoleSubscriptionViewModel.ViewState>(ViewState.Loading) {

    sealed class Event {
        object SubmitSuccess : Event()
        data class SubmitFailure(val error: RestApiError) : Event()
        data class StoreStateUpdate(
            val guildRoleSubscriptionGroupListing: GuildRoleSubscriptionGroupListing,
            val fullServerGatingOverwrite: Boolean? = null,
        ) : Event()
    }

    data class StoreState(
        val guildRoleSubscriptionGroupState: StoreGuildRoleSubscriptions.GuildRoleSubscriptionGroupState?,
        val fullServerGatingOverwrite: Boolean?,
    )

    sealed class ViewState {
        object Loading : ViewState()
        object Empty : ViewState()
        object Failed : ViewState()
        data class Loaded(
            val hasChanges: Boolean = false,
            val isSubmitting: Boolean = false,
            val groupListingId: Long,
        ) : ViewState()
    }

    private var editedGroupCoverImage: String? = null
    private var editedGroupDescription: String? = null
    private var editedIsFullServerGating: Boolean? = null
    private var fullServerGatingOverwrite: Boolean? = null
    private var guildRoleSubscriptionGroupListing: GuildRoleSubscriptionGroupListing? = null
    private val eventSubject = PublishSubject.create<Event>()

    init {
        storeGuildRoleSubscriptions.fetchGuildRoleSubscriptionGroupsForGuild(guildId)
        storeObservable.distinctUntilChanged().ui(this).appSubscribe(
            ServerSettingsGuildRoleSubscriptionViewModel::class.java,
            errorHandler = { updateViewState(ViewState.Failed) },
            onNext = ::handleStoreState,
        )
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun onGuildRoleSubscriptionGroupUpdated(
        description: String?,
        coverImage: String?,
        isFullServerGating: Boolean,
    ) {
        val loaded = viewState as? ViewState.Loaded ?: return
        val descriptionChanged = description != guildRoleSubscriptionGroupListing?.description && !description.isNullOrBlank()
        editedGroupDescription = description.takeIf { descriptionChanged }

        val coverImageChanged = !coverImage.isNullOrBlank()
        editedGroupCoverImage = coverImage.takeIf { coverImageChanged }

        val originalGating = fullServerGatingOverwrite ?: guildRoleSubscriptionGroupListing?.fullServerGate
        val gatingChanged = originalGating != isFullServerGating
        editedIsFullServerGating = isFullServerGating.takeIf { gatingChanged }
        updateViewState(loaded.copy(hasChanges = descriptionChanged || coverImageChanged || gatingChanged))
    }

    fun submitChanges() {
        val listing = guildRoleSubscriptionGroupListing ?: return
        val loaded = viewState as? ViewState.Loaded ?: return
        updateViewState(loaded.copy(isSubmitting = true))
        GuildRoleSubscriptionUtils.updateGuildRoleSubscriptionGroupListing(
            restApi,
            storeGuildRoleSubscriptions,
            guildId,
            listing.id,
            editedGroupCoverImage,
            editedGroupDescription,
            editedIsFullServerGating,
        ).ui(this).appSubscribe(
            ServerSettingsGuildRoleSubscriptionViewModel::class.java,
            errorHandler = { error ->
                updateViewState(loaded.copy(isSubmitting = false))
                eventSubject.onNext(Event.SubmitFailure(error))
            },
        ) {
            updateViewState(loaded.copy(hasChanges = false, isSubmitting = false))
            eventSubject.onNext(Event.SubmitSuccess)
        }
    }

    private fun handleStoreState(storeState: StoreState) {
        when (val state = storeState.guildRoleSubscriptionGroupState) {
            null, StoreGuildRoleSubscriptions.GuildRoleSubscriptionGroupState.Loading ->
                updateViewState(ViewState.Loading)
            is StoreGuildRoleSubscriptions.GuildRoleSubscriptionGroupState.Failed ->
                updateViewState(ViewState.Failed)
            is StoreGuildRoleSubscriptions.GuildRoleSubscriptionGroupState.Loaded -> {
                val listing = state.guildRoleSubscriptionGroupListing
                if (listing == null) {
                    updateViewState(ViewState.Empty)
                    return
                }
                val current = viewState as? ViewState.Loaded
                val hasChanges = current?.hasChanges == true
                if (!hasChanges) {
                    guildRoleSubscriptionGroupListing = listing
                    eventSubject.onNext(Event.StoreStateUpdate(listing, storeState.fullServerGatingOverwrite))
                }
                updateViewState(ViewState.Loaded(hasChanges, current?.isSubmitting == true, listing.id))
            }
        }
    }

    companion object {
        private fun observeStoreState(
            guildId: Long,
            observationDeck: ObservationDeck,
            storeGuildRoleSubscriptions: StoreGuildRoleSubscriptions,
            storeGuilds: StoreGuilds,
        ): Observable<StoreState> = observationDeck.connectRx(
            storeGuildRoleSubscriptions,
            storeGuilds,
        ) {
            val state = storeGuildRoleSubscriptions.getGuildRoleSubscriptionState(guildId)
            val overwrite = (state as? StoreGuildRoleSubscriptions.GuildRoleSubscriptionGroupState.Loaded)
                ?.guildRoleSubscriptionGroupListing
                ?.getFullServerGatingOverwrite(storeGuilds.getRoles()[guildId])
            StoreState(state, overwrite)
        }
    }
}
