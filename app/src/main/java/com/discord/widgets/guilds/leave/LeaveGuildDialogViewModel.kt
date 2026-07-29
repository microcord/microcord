package com.discord.widgets.guilds.leave

import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreLurking
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject

class LeaveGuildDialogViewModel(
    private val guildId: Long,
    private val restApi: RestAPIInterface = RestAPI.api,
    private val lurkingStore: StoreLurking = StoreStream.getLurking(),
    storeStateObservable: Observable<StoreState> = observeStoreState(
        guildId,
        StoreStream.getGuilds(),
        lurkingStore,
    ),
) : AppViewModel<LeaveGuildDialogViewModel.ViewState>() {

    sealed class StoreState {
        object Invalid : StoreState()
        data class Valid(val guild: Guild, val isLurking: Boolean) : StoreState()
    }

    sealed class ViewState {
        object Invalid : ViewState()
        data class Valid(val guild: Guild, val isLoading: Boolean, val isLurking: Boolean) : ViewState()
    }

    sealed class Event {
        object Dismiss : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()

    init {
        storeStateObservable.ui(this).appSubscribe(LeaveGuildDialogViewModel::class.java) { state ->
            val loading = (viewState as? ViewState.Valid)?.isLoading == true
            updateViewState(
                when (state) {
                    StoreState.Invalid -> ViewState.Invalid
                    is StoreState.Valid -> ViewState.Valid(state.guild, loading, state.isLurking)
                },
            )
        }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun leaveGuild() {
        val state = viewState as? ViewState.Valid ?: return
        updateViewState(state.copy(isLoading = true))
        if (state.isLurking) {
            lurkingStore.stopLurking(guildId, ::onLeaveSucceeded, ::onLeaveFailed)
        } else {
            restApi.leaveGuild(guildId)
                .restSubscribeOn()
                .ui(this)
                .appSubscribe(
                    LeaveGuildDialogViewModel::class.java,
                    errorHandler = { onLeaveFailed() },
                ) { onLeaveSucceeded() }
        }
    }

    private fun onLeaveSucceeded() = eventSubject.onNext(Event.Dismiss)

    private fun onLeaveFailed() {
        val state = viewState as? ViewState.Valid ?: return
        updateViewState(state.copy(isLoading = false))
    }

    companion object {
        fun observeStoreState(
            guildId: Long,
            guildStore: StoreGuilds,
            lurkingStore: StoreLurking,
        ): Observable<StoreState> = Observable.combineLatest(
            guildStore.observeGuild(guildId),
            lurkingStore.isLurkingObs(guildId),
        ) { guild, lurking -> guild?.let { StoreState.Valid(it, lurking) } ?: StoreState.Invalid }
    }
}
