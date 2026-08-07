package com.discord.widgets.guilds.contextmenu

import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject
import com.discord.api.channel.isThreadOnlyChannel

/**
 * State for the guild long-press menu.
 *
 * The menu stays subscribed while it is up, so leaving the guild in another surface — or the guild
 * simply going away — collapses it to [ViewState.Invalid], which the fragment turns into a
 * dismissal rather than a stale card.
 */
class GuildContextMenuViewModel(
    private val guildId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
) : AppViewModel<GuildContextMenuViewModel.ViewState>() {

    private val eventSubject = PublishSubject.create<Event>()

    init {
        storeStateObservable
            .ui(this)
            .appSubscribe(GuildContextMenuViewModel::class.java) { handleStoreState(it) }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    private fun handleStoreState(storeState: StoreState) {
        when (storeState) {
            is StoreState.Valid -> updateViewState(
                ViewState.Valid(
                    guild = storeState.guild,
                    showMarkAsRead = storeState.isGuildUnread,
                    // The owner cannot leave their own guild; they delete or transfer it instead.
                    showLeaveGuild = !storeState.guild.isOwner(storeState.myUserId),
                    isGuildSelected = storeState.selectedGuildId == storeState.guild.id,
                ),
            )
            StoreState.Invalid -> updateViewState(ViewState.Invalid)
        }
    }

    fun onMarkAsReadClicked() {
        StoreStream.getMessageAck().bulkAck(listOf(guildId)) {
            StoreStream.getChannels().getChannel(it)?.isThreadOnlyChannel() == true
        }
        eventSubject.onNext(Event.Dismiss)
    }

    sealed class Event {
        object Dismiss : Event()
    }

    sealed class StoreState {
        object Invalid : StoreState()

        data class Valid(
            val guild: Guild,
            val myUserId: Long,
            val isGuildUnread: Boolean,
            val selectedGuildId: Long,
        ) : StoreState()
    }

    sealed class ViewState {
        object Invalid : ViewState()

        data class Valid(
            val guild: Guild,
            val showMarkAsRead: Boolean,
            val showLeaveGuild: Boolean,
            val isGuildSelected: Boolean,
        ) : ViewState()
    }

    companion object {
        fun observeStoreState(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getUsers().observeMe(),
            StoreStream.getReadStates().getIsUnread(guildId),
            StoreStream.getGuildSelected().observeSelectedGuildId(),
        ) { guild, me, isUnread, selectedGuildId ->
            if (guild == null) {
                StoreState.Invalid
            } else {
                StoreState.Valid(guild, me.id, isUnread, selectedGuildId)
            }
        }
    }
}
