package com.discord.stores

import android.content.Context
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import rx.Observable

class StoreExpandedGuildFolders(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
) : StoreV2() {
    private val openFolderIds = HashSet<Long>()
    private var openFolderIdsSnapshot: Set<Long> = emptySet()
    private val openFolderIdsCache = Persister(CACHE_KEY_OPEN_FOLDERS, HashSet<Long>())

    fun getOpenFolderIds(): Set<Long> = openFolderIdsSnapshot
    fun observeOpenFolderIds(): Observable<Set<Long>> =
        observationDeck.connectRx(this, generator = ::getOpenFolderIds).distinctUntilChanged()

    fun openFolder(folderId: Long) = dispatcher.schedule { handleFolderOpened(folderId) }
    fun closeFolder(folderId: Long) = dispatcher.schedule { handleFolderClosed(folderId) }

    fun handleFolderOpened(folderId: Long) {
        openFolderIds.add(folderId)
        markChanged()
    }

    fun handleFolderClosed(folderId: Long) {
        openFolderIds.remove(folderId)
        markChanged()
    }

    override fun init(context: Context) = restore()

    internal fun restore(ids: Set<Long> = openFolderIdsCache.get()) {
        openFolderIds.clear()
        openFolderIds.addAll(ids)
        markChanged()
    }

    internal fun reset() {
        openFolderIds.clear()
        openFolderIdsCache.clear(persist = true)
        markChanged()
    }

    override fun snapshotData() {
        openFolderIdsSnapshot = openFolderIds.toSet()
        openFolderIdsCache.set(HashSet(openFolderIds), persist = true)
    }

    companion object {
        const val CACHE_KEY_OPEN_FOLDERS = "CACHE_KEY_OPEN_FOLDERS"
    }
}
