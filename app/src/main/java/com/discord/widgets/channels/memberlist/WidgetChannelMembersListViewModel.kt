package com.discord.widgets.channels.memberlist

import com.discord.api.channel.Channel
import com.discord.app.AppViewModel
import com.discord.utilities.rx.ui
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter
import rx.Observable
import rx.subjects.PublishSubject

class WidgetChannelMembersListViewModel(
    stateSource: Observable<WidgetChannelMembersList.State>,
    private val subscriber: GuildChannelSubscriber = object : GuildChannelSubscriber {
        override fun subscribeToChannelRange(channel: Channel, range: IntRange) =
            com.discord.stores.StoreStream.getGuildSubscriptions().subscribeChannelRange(channel.guildId, channel.id, range)

        override fun subscribeToThread(channel: Channel) =
            com.discord.stores.StoreStream.getGuildSubscriptions().subscribeThread(channel.guildId, channel.id)
    },
) : AppViewModel<WidgetChannelMembersListViewModel.ViewState>(ViewState.Empty) {
    interface GuildChannelSubscriber {
        fun subscribeToChannelRange(channel: Channel, range: IntRange)
        fun subscribeToThread(channel: Channel)
    }

    interface MemberList {
        val listId: String
        val size: Int
        operator fun get(index: Int): ChannelMembersListAdapter.Item
        fun getHeaderPositionForItem(itemPosition: Int): Int?
    }

    sealed class ViewState(open val listId: String) {
        object Empty : ViewState("")
        data class Loaded(
            val listItems: MemberList,
            val isOpen: Boolean,
            val channel: Channel,
            val isThreadJoined: Boolean,
        ) : ViewState(listItems.listId)
    }

    sealed class Event {
        data class Error(val code: Int) : Event()
        object ScrollToTop : Event()
        object UpdateRanges : Event()
    }

    private val events = PublishSubject.create<Event>()

    init {
        stateSource.ui(this).subscribe { state ->
            val channel = state.channel
            if (channel == null) {
                updateViewState(ViewState.Empty)
                return@subscribe
            }
            val previous = viewState
            val loaded = ViewState.Loaded(state.listItems, state.panelOpen, channel, state.isThreadJoined)
            updateViewState(loaded)
            updateSubscriptions(previous, loaded)
        }
    }

    fun observeEvents(): Observable<Event> = events
    fun emitError(code: Int) = events.onNext(Event.Error(code))
    fun scrollToTop() = events.onNext(Event.ScrollToTop)

    fun updateSubscriptionsForChannel(range: IntRange) {
        val loaded = viewState as? ViewState.Loaded ?: return
        if (!loaded.isOpen || isThread(loaded.channel)) return
        subscriber.subscribeToChannelRange(loaded.channel, range)
    }

    private fun updateSubscriptions(previous: ViewState?, loaded: ViewState.Loaded) {
        if (!loaded.isOpen) return
        val opened = previous !is ViewState.Loaded || !previous.isOpen
        val changedList = previous?.listId != loaded.listId
        if (!opened && !changedList) return
        if (changedList) events.onNext(Event.ScrollToTop)
        if (isThread(loaded.channel)) {
            subscriber.subscribeToThread(loaded.channel)
        } else if (changedList) {
            updateSubscriptionsForChannel(0 until 100)
        } else {
            events.onNext(Event.UpdateRanges)
        }
    }

    private fun isThread(channel: Channel) = channel.type == Channel.ANNOUNCEMENT_THREAD ||
        channel.type == Channel.PUBLIC_THREAD || channel.type == Channel.PRIVATE_THREAD
}
