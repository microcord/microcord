package com.discord.widgets.guilds.contextmenu

import com.discord.app.AppViewModel
import com.discord.models.domain.ModelGuildFolder
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject

/**
 * State for the folder long-press menu.
 *
 * The folder is looked up in the account's own guild-folder settings rather than in the sidebar's
 * sorted view, because that is the record "Folder Settings" edits. A folder that disappears from
 * those settings collapses the menu to [ViewState.Invalid].
 */
class FolderContextMenuViewModel(
    private val folderId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(folderId),
) : AppViewModel<FolderContextMenuViewModel.ViewState>() {

    private val eventSubject = PublishSubject.create<Event>()

    /** Kept from the last store emission so "Mark As Read" knows what to acknowledge. */
    private var guildIds: List<Long> = emptyList()
    private var unreadGuildIds: Set<Long> = emptySet()

    init {
        storeStateObservable
            .ui(this)
            .appSubscribe(FolderContextMenuViewModel::class.java) { handleStoreState(it) }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    private fun handleStoreState(storeState: StoreState) {
        when (storeState) {
            is StoreState.Valid -> {
                guildIds = storeState.folder.guildIds
                unreadGuildIds = storeState.unreadGuildIds
                updateViewState(
                    ViewState.Valid(
                        folder = storeState.folder,
                        showMarkAsRead = guildIds.any { it in unreadGuildIds },
                    ),
                )
            }
            StoreState.Invalid -> updateViewState(ViewState.Invalid)
        }
    }

    /** Acknowledges every unread guild in the folder, then dismisses once they have all landed. */
    fun onMarkAsReadClicked() {
        val calls = guildIds.intersect(unreadGuildIds).map { RestAPI.api.ackGuild(it).restSubscribeOn() }
        Observable.merge(Observable.from(calls))
            .toList()
            .ui(this)
            .appSubscribe(FolderContextMenuViewModel::class.java) { eventSubject.onNext(Event.Dismiss) }
    }

    fun onSettingsClicked() {
        eventSubject.onNext(Event.Dismiss)
    }

    sealed class Event {
        object Dismiss : Event()
    }

    sealed class StoreState {
        object Invalid : StoreState()
        data class Valid(val folder: ModelGuildFolder, val unreadGuildIds: Set<Long>) : StoreState()
    }

    sealed class ViewState {
        object Invalid : ViewState()
        data class Valid(val folder: ModelGuildFolder, val showMarkAsRead: Boolean) : ViewState()
    }

    companion object {
        fun observeStoreState(folderId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getUserSettings().observeGuildFolders(),
            StoreStream.getReadStates().getUnreadGuildIds(),
        ) { guildFolders, unreadGuildIds ->
            val folder = guildFolders.firstOrNull { it.id == folderId }
            if (folder == null) StoreState.Invalid else StoreState.Valid(folder, unreadGuildIds)
        }
    }
}
