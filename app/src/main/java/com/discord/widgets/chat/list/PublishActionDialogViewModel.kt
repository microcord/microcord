package com.discord.widgets.chat.list

import androidx.annotation.StringRes
import com.discord.R
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelChannelFollowerStats
import com.discord.models.domain.ModelChannelFollowerStatsDto
import com.discord.restapi.RestAPIInterface
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject

class PublishActionDialogViewModel(
    private val messageId: Long,
    private val channelId: Long,
    private val restApi: RestAPIInterface = RestAPI.api,
    storeObservable: Observable<StoreState> = observeChannelFollowerStats(channelId, restApi),
) : AppViewModel<PublishActionDialogViewModel.ViewState>(ViewState.Loading) {

    sealed class Event {
        data class Failure(@StringRes val failureMessageStringRes: Int) : Event()
        data class Success(@StringRes val successMessageStringRes: Int) : Event()
    }

    class StoreState(val followerStats: ModelChannelFollowerStats?)

    sealed class ViewState {
        object Loading : ViewState()
        object LoadedNoFollowers : ViewState()
        data class LoadedHasFollowers(val followerStats: ModelChannelFollowerStats) : ViewState()
    }

    private val eventSubject = PublishSubject.create<Event>()

    init {
        storeObservable
            .computationLatest()
            .ui(this)
            .appSubscribe(PublishActionDialogViewModel::class.java, onNext = ::handleStoreState)
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun publishMessage() {
        restApi.crosspostMessage(channelId, messageId)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                PublishActionDialogViewModel::class.java,
                errorHandler = { eventSubject.onNext(Event.Failure(R.string.default_failure_to_perform_action_message)) },
            ) { eventSubject.onNext(Event.Success(R.string.message_published)) }
    }

    private fun handleStoreState(storeState: StoreState) {
        val stats = storeState.followerStats
        updateViewState(
            if (stats != null && (stats.guildsFollowing ?: 0) > 0) ViewState.LoadedHasFollowers(stats)
            else ViewState.LoadedNoFollowers,
        )
    }

    companion object {
        private fun observeChannelFollowerStats(
            channelId: Long,
            restApi: RestAPIInterface,
        ): Observable<StoreState> = restApi.getChannelFollowerStats(channelId)
            .map(::toStoreState)
            .onErrorReturn { StoreState(null) }
            .distinctUntilChanged()

        private fun toStoreState(response: ModelChannelFollowerStatsDto?): StoreState =
            StoreState(response?.let { ModelChannelFollowerStats.fromResponse(it) })
    }
}
