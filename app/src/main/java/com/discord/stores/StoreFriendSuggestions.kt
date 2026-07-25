package com.discord.stores

import com.discord.api.friendsuggestions.FriendSuggestion as ApiFriendSuggestion
import com.discord.api.friendsuggestions.FriendSuggestionDelete
import com.discord.api.user.User as ApiUser
import com.discord.models.domain.ModelPayload
import com.discord.models.friendsuggestions.FriendSuggestion
import com.discord.models.user.CoreUser
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

class StoreFriendSuggestions(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val fetchSuggestions: ((List<ApiFriendSuggestion>) -> Unit, () -> Unit) -> Unit,
    private val hydrateUsers: (List<ApiUser>) -> Unit = {},
) : StoreV2() {
    private val suggestions = HashMap<Long, FriendSuggestion>()
    private var loading = false
    private var suggestionsSnapshot: Map<Long, FriendSuggestion> = emptyMap()
    private var loadingSnapshot = false

    fun getFriendSuggestions(): Map<Long, FriendSuggestion> = suggestionsSnapshot
    fun isLoading(): Boolean = loadingSnapshot
    fun observeFriendSuggestions(): Observable<Map<Long, FriendSuggestion>> =
        observationDeck.connectRx(this) { suggestionsSnapshot }.distinctUntilChanged()

    @StoreThread
    fun handleConnectionOpen(readyPayload: ModelPayload) {
        StoreThreadScheduler.assertCurrentThread()
        suggestions.clear()
        loading = readyPayload.friendSuggestionCount > 0
        markChanged()
        if (loading) fetchSuggestions(
            { loaded -> dispatcher.schedule { handleFriendSuggestionsLoaded(loaded) } },
            { dispatcher.schedule { handleFriendSuggestionsLoadFailure() } },
        )
    }

    @StoreThread
    fun handleFriendSuggestionCreate(friendSuggestion: ApiFriendSuggestion) {
        StoreThreadScheduler.assertCurrentThread()
        hydrateUsers(listOf(friendSuggestion.suggestedUser))
        val converted = friendSuggestion.toModel()
        suggestions[converted.user.id] = converted
        markChanged()
    }

    @StoreThread
    fun handleFriendSuggestionDelete(friendSuggestionDelete: FriendSuggestionDelete) {
        StoreThreadScheduler.assertCurrentThread()
        if (suggestions.remove(friendSuggestionDelete.suggestedUserId) != null) markChanged()
    }

    @StoreThread
    fun handleFriendSuggestionsLoadFailure() {
        StoreThreadScheduler.assertCurrentThread()
        suggestions.clear()
        loading = false
        markChanged()
    }

    @StoreThread
    fun handleFriendSuggestionsLoaded(loadedSuggestions: List<ApiFriendSuggestion>) {
        StoreThreadScheduler.assertCurrentThread()
        suggestions.clear()
        hydrateUsers(loadedSuggestions.map(ApiFriendSuggestion::suggestedUser))
        loadedSuggestions.forEach { suggestion ->
            suggestion.toModel().let { suggestions[it.user.id] = it }
        }
        loading = false
        markChanged()
    }

    fun updateFriendSuggestions(suggestions: List<ApiFriendSuggestion>) =
        dispatcher.schedule { handleFriendSuggestionsLoaded(suggestions) }

    @StoreThread
    fun handlePreLogout() {
        StoreThreadScheduler.assertCurrentThread()
        suggestions.clear()
        loading = false
        markChanged()
    }

    override fun snapshotData() {
        suggestionsSnapshot = HashMap(suggestions)
        loadingSnapshot = loading
    }

    private fun ApiFriendSuggestion.toModel() =
        FriendSuggestion(CoreUser(suggestedUser), reasons.firstOrNull()?.name)
}
