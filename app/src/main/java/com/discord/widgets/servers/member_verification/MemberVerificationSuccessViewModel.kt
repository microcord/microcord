package com.discord.widgets.servers.member_verification

import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject

class MemberVerificationSuccessViewModel(
    val guildId: Long,
    private val restAPI: RestAPIInterface = RestAPI.api,
    storeObservable: Observable<StoreState> = observeStores(guildId),
) : AppViewModel<MemberVerificationSuccessViewModel.ViewState>() {

    sealed class Event {
        object Error : Event()
        object Success : Event()
    }

    data class StoreState(val lastSeen: String?, val guildName: String?)

    sealed class ViewState {
        object Acked : ViewState()
        data class Loaded(val guildName: String?) : ViewState()
    }

    private val eventSubject = PublishSubject.create<Event>()

    init {
        storeObservable.computationLatest().ui(this)
            .appSubscribe(MemberVerificationSuccessViewModel::class.java) { storeState ->
                updateViewState(reduce(storeState))
            }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun ackGuildJoinRequest() {
        restAPI.ackGuildJoinRequest(guildId).restSubscribeOn().ui(this).appSubscribe(
            errorClass = MemberVerificationSuccessViewModel::class.java,
            errorHandler = { eventSubject.onNext(Event.Error) },
            onCompleted = { eventSubject.onNext(Event.Success) },
            onNext = {},
        )
    }

    companion object {
        internal fun reduce(storeState: StoreState): ViewState =
            if (storeState.lastSeen != null) ViewState.Acked else ViewState.Loaded(storeState.guildName)

        private fun observeStores(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuildJoinRequests().observeGuildJoinRequest(guildId),
            StoreStream.getGuilds().observeGuild(guildId),
        ) { joinRequest, guild -> StoreState(joinRequest?.lastSeen, guild?.name) }
    }
}
