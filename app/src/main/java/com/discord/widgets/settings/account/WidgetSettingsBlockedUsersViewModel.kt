package com.discord.widgets.settings.account

import androidx.annotation.StringRes
import com.discord.R
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.StoreUserRelationships
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rest.contextPropertiesOf
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils
import rx.Observable
import rx.subjects.PublishSubject

class WidgetSettingsBlockedUsersViewModel(
    storeObservable: Observable<StoreState> = observeStores(
        StoreStream.getUserRelationships(),
        StoreStream.getUsers(),
    ),
) : AppViewModel<WidgetSettingsBlockedUsersViewModel.ViewState>(ViewState.Uninitialized) {
    data class StoreState(val users: Map<Long, User>)

    data class Item(val user: User) : MGRecyclerDataPayload {
        override val key = user.id.toString()
        override val type = VIEW_TYPE_BLOCKED_USER_ITEM
    }

    sealed class ViewState {
        data object Uninitialized : ViewState()
        data object Empty : ViewState()
        data class Loaded(val items: List<Item>) : ViewState()
    }

    sealed class Event {
        data class ShowToast(@StringRes val stringRes: Int) : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()

    init {
        storeObservable.ui(this).appSubscribe(WidgetSettingsBlockedUsersViewModel::class.java) { state ->
            val items = state.users.values
                .sortedWith { first, second -> UserUtils.compareUserNames(first, second) }
                .map(::Item)
            updateViewState(if (items.isEmpty()) ViewState.Empty else ViewState.Loaded(items))
        }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun onClickUnblock(userId: Long) {
        val api = RestAPI.apiOrNull ?: return
        api.removeRelationship(userId, relationshipContext())
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetSettingsBlockedUsersViewModel::class.java,
                errorHandler = { eventSubject.onNext(Event.ShowToast(R.string.default_failure_to_perform_action_message)) },
            ) { eventSubject.onNext(Event.ShowToast(R.string.user_has_been_unblocked)) }
    }

    companion object {
        const val VIEW_TYPE_BLOCKED_USER_ITEM = 0
        private const val LOCATION = "Blocked Users List"

        private fun relationshipContext(): String = contextPropertiesOf("location" to LOCATION)

        fun observeStores(
            storeUserRelationships: StoreUserRelationships,
            storeUser: StoreUser,
        ): Observable<StoreState> = storeUserRelationships.observe()
            .switchMap { relationships ->
                storeUser.observeUsers(
                    relationships.filterValues { it == ModelUserRelationship.TYPE_BLOCKED }.keys,
                )
            }
            .map(::StoreState)
    }
}
