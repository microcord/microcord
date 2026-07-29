package com.discord.widgets.channels.memberlist

import com.discord.api.channel.Channel
import com.discord.app.AppViewModel
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject

class WidgetChannelMembersListViewModel(
    stateSource: Observable<WidgetChannelMembersList.State>,
) : AppViewModel<WidgetChannelMembersListViewModel.ViewState>(ViewState.Empty) {
    sealed class ViewState(open val listId: String) {
        object Empty : ViewState("")
        data class Loaded(
            val listItems: List<ChannelMemberListRow>,
            val isOpen: Boolean,
            val channel: Channel,
            val isThreadJoined: Boolean,
        ) : ViewState(channel.memberListId ?: channel.id.toString())
    }

    sealed class Event {
        data class Error(val code: Int) : Event()
        object ScrollToTop : Event()
    }

    private val events = PublishSubject.create<Event>()

    init {
        stateSource.ui(this).subscribe { state ->
            val channel = state.channel
            updateViewState(if (channel == null) {
                ViewState.Empty
            } else {
                ViewState.Loaded(
                    state.rows,
                    state.panelOpen,
                    channel,
                    state.rows.filterIsInstance<ChannelMemberListRow.JoinLeaveThread>()
                        .firstOrNull()?.joined == true,
                )
            })
        }
    }

    fun observeEvents(): Observable<Event> = events
    fun emitError(code: Int) = events.onNext(Event.Error(code))
    fun scrollToTop() = events.onNext(Event.ScrollToTop)
}
