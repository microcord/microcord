package com.discord.stores

import com.discord.api.message.Message
import com.discord.api.savedmessage.SavedMessage
import com.discord.api.savedmessage.SavedMessageDelete
import com.discord.api.savedmessage.SavedMessagesResponse
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import java.util.Collections
import java.util.concurrent.CancellationException
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.schedulers.Schedulers
import com.discord.models.message.Message as DomainMessage

/** Account-local, memory-only saved messages. A non-null dueAt denotes a reminder, not a bookmark. */
class StoreSavedMessages(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val restApi: () -> RestAPIInterface?,
    private val requestScheduler: Scheduler = Schedulers.io(),
) : StoreV2() {
    data class State(val entries: Map<String, SavedMessage>, val loading: Boolean, val error: Throwable?) {
        val bookmarks: List<SavedMessage>
            get() = Collections.unmodifiableList(
                entries.values.filter { it.saveData.dueAt == null }.sortedByDescending { it.saveData.savedAt },
            )
    }

    private val entries = LinkedHashMap<String, SavedMessage>()
    private val channelIndex = HashMap<Long, MutableSet<Long>>()
    private val missingMessageIds = HashSet<Long>()
    private val overdueMessageIds = HashSet<Long>()
    @Volatile private var stale = true
    @Volatile private var lastChanged = 0L
    @Volatile private var generation = 0L
    @Volatile private var revision = 0L
    @Volatile private var accountId: Long? = null
    @Volatile private var snapshot = State(emptyMap(), false, null)
    @Volatile private var overdueCount = 0
    private var loading = false
    private var error: Throwable? = null
    private var fetchSubscription: Subscription? = null
    private var invalidationRevision = 0L
    private var fetchChanges: LinkedHashMap<String, FetchChange>? = null

    private class FetchChange {
        var replacesEntry = false
        var replacement: SavedMessage? = null
        var messageUpdate: Message? = null
        var messageDeleted = false
    }

    fun observe(): Observable<State> = observationDeck.connectRx(this) { snapshot }.distinctUntilChanged()
    fun getState(): State = snapshot
    fun isSaved(channelId: Long, messageId: Long): Boolean = key(channelId, messageId) in snapshot.entries
    fun isBookmarked(channelId: Long, messageId: Long): Boolean =
        snapshot.entries[key(channelId, messageId)]?.let { it.saveData.dueAt == null } == true
    fun getIsStale(): Boolean = stale
    fun getLastChanged(): Long = lastChanged
    fun getOverdueMessageReminderCount(): Int = overdueCount

    fun fetch() {
        val expectedGeneration = generation
        dispatcher.schedule { if (isCurrent(expectedGeneration)) fetchInternal() }
    }

    fun remove(channelId: Long, messageId: Long): Observable<Void> {
        require(channelId > 0 && messageId > 0)
        val expectedGeneration = generation
        return Observable.defer {
            if (!isCurrent(expectedGeneration)) throw CancellationException("Saved-message account changed")
            val expectedRevision = revision
            val api = checkNotNull(restApi()) { "REST API is not initialized" }
            api.deleteSavedMessage(channelId, messageId)
                .filter { isCurrent(expectedGeneration) }
                .onErrorResumeNext { failure: Throwable ->
                    if (isCurrent(expectedGeneration)) Observable.error(failure) else Observable.empty()
                }
                // Void transports may complete without an onNext value.
                .doOnCompleted {
                    dispatcher.schedule {
                        if (isCurrent(expectedGeneration)) {
                            if (revision == expectedRevision) {
                                handleSavedMessageDelete(SavedMessageDelete(channelId, messageId))
                            } else if (key(channelId, messageId) in entries) {
                                invalidate()
                            }
                        }
                    }
                }
        }
    }

    @StoreThread
    fun handleConnectionOpen(userId: Long) {
        if (userId <= 0) return
        if (accountId != userId) {
            handlePreLogout()
            accountId = userId
        }
        invalidate()
    }

    /** Called after channel/guild/permission snapshots change; available entries need no refetch. */
    fun invalidateUnavailable(channelId: Long? = null) {
        val expectedGeneration = generation
        dispatcher.schedule {
            if (isCurrent(expectedGeneration) && missingMessageIds.isNotEmpty() &&
                (channelId == null || channelIndex[channelId].orEmpty().any(missingMessageIds::contains))
            ) invalidate()
        }
    }

    @StoreThread
    fun handleSavedMessageCreate(saved: SavedMessage) {
        if (accountId == null) return
        val id = key(saved.saveData.channelId, saved.saveData.messageId)
        pendingChange(id)?.apply {
            replacesEntry = true
            replacement = saved
            messageUpdate = null
            messageDeleted = false
        }
        val previous = entries.put(id, saved)
        if (previous != saved) changed(membershipChanged = previous == null)
    }

    @StoreThread
    fun handleSavedMessageDelete(deleted: SavedMessageDelete) {
        if (accountId == null) return
        val id = key(deleted.channelId, deleted.messageId)
        pendingChange(id)?.apply {
            replacesEntry = true
            replacement = null
            messageUpdate = null
            messageDeleted = false
        }
        if (entries.remove(id) != null) changed(membershipChanged = true)
    }

    @StoreThread
    fun handleMessageUpdate(message: Message) {
        // This revision guards delayed REST removals, not the full-list fetch.
        if (loading) revision++
        val id = key(message.channelId, message.id)
        pendingChange(id)?.apply {
            if (!messageDeleted) messageUpdate = messageUpdate?.let { mergeMessage(it, message) } ?: message
        }
        val saved = entries[id] ?: return
        val previous = saved.message ?: return
        val merged = mergeMessage(previous, message)
        if (merged != previous) {
            entries[id] = saved.copy(message = merged)
            changed()
        }
    }

    @StoreThread
    fun handleMessageDelete(channelId: Long, messageIds: Collection<Long>) {
        if (loading && messageIds.isNotEmpty()) revision++
        var updated = false
        messageIds.forEach { messageId ->
            val id = key(channelId, messageId)
            pendingChange(id)?.apply {
                messageDeleted = true
                messageUpdate = null
            }
            val saved = entries[id]
            if (saved?.message != null) {
                entries[id] = saved.copy(message = null)
                updated = true
            }
        }
        if (updated) changed()
    }

    @StoreThread
    fun handleMessageReminderDue(saved: SavedMessage) {
        val current = entries[key(saved.saveData.channelId, saved.saveData.messageId)] ?: return
        val dueAt = current.saveData.dueAt ?: return
        if (dueAt.dateTimeMillis <= System.currentTimeMillis() && overdueMessageIds.add(current.saveData.messageId)) {
            markChanged()
        }
    }

    @StoreThread
    fun handlePreLogout() {
        generation++
        accountId = null
        fetchSubscription?.unsubscribe()
        fetchSubscription = null
        fetchChanges?.clear()
        fetchChanges = null
        entries.clear()
        channelIndex.clear()
        missingMessageIds.clear()
        overdueMessageIds.clear()
        loading = false
        error = null
        stale = true
        lastChanged = 0
        revision = 0
        invalidationRevision = 0
        markChanged()
    }

    @StoreThread
    private fun invalidate() {
        stale = true
        revision++
        invalidationRevision++
        fetch()
    }

    @StoreThread
    private fun fetchInternal() {
        if (!stale || loading) return
        val expectedGeneration = generation
        val expectedInvalidation = invalidationRevision
        val pending = LinkedHashMap<String, FetchChange>()
        fetchChanges = pending
        loading = true
        error = null
        markChanged()
        fetchSubscription = Observable.defer<SavedMessagesResponse> {
            if (!isCurrent(expectedGeneration)) throw CancellationException("Saved-message account changed")
            checkNotNull(restApi()) { "REST API is not initialized" }.getSavedMessages()
        }.subscribeOn(requestScheduler).single().subscribe(
            { response -> dispatcher.schedule {
                if (isCurrent(expectedGeneration) && fetchChanges === pending) {
                    loading = false
                    fetchSubscription = null
                    fetchChanges = null
                    if (invalidationRevision != expectedInvalidation) {
                        invalidate()
                    } else {
                        val replaced = response.results.associateByTo(LinkedHashMap()) {
                            key(it.saveData.channelId, it.saveData.messageId)
                        }
                        pending.forEach { (id, change) ->
                            val saved = if (change.replacesEntry) change.replacement else replaced[id]
                            val message = saved?.message
                            val update = change.messageUpdate
                            if (saved == null) {
                                replaced.remove(id)
                            } else {
                                replaced[id] = when {
                                    change.messageDeleted -> saved.copy(message = null)
                                    message != null && update != null -> saved.copy(message = mergeMessage(message, update))
                                    else -> saved
                                }
                            }
                        }
                        val membershipChanged = entries.keys != replaced.keys
                        entries.clear()
                        entries.putAll(replaced)
                        stale = false
                        error = null
                        changed(membershipChanged)
                    }
                    pending.clear()
                    markChanged()
                }
            } },
            { failure -> dispatcher.schedule {
                if (isCurrent(expectedGeneration) && fetchChanges === pending) {
                    fetchSubscription = null
                    fetchChanges = null
                    pending.clear()
                    loading = false
                    stale = true
                    error = failure
                    markChanged()
                }
            } },
        )
    }

    @StoreThread
    private fun pendingChange(id: String): FetchChange? {
        val pending = fetchChanges ?: return null
        pending[id]?.let { return it }
        // Initial fetches have unknown membership, so retain patches even for uncached IDs.
        // ponytail: one coalesced change per ID, capped at 1024; overflow fails closed, never auto-retries.
        if (pending.size >= 1024) {
            fetchChanges = null
            pending.clear()
            fetchSubscription?.unsubscribe()
            fetchSubscription = null
            loading = false
            stale = true
            error = IllegalStateException("Too many message changes during saved-message refresh. Try again.")
            markChanged()
            return null
        }
        return FetchChange().also { pending[id] = it }
    }

    private fun mergeMessage(previous: Message, update: Message): Message =
        DomainMessage(previous).merge(update).synthesizeApiMessage().copy(hit = update.hit ?: previous.hit)

    @StoreThread
    private fun changed(membershipChanged: Boolean = false) {
        revision++
        if (membershipChanged) lastChanged = System.currentTimeMillis()
        channelIndex.clear()
        missingMessageIds.clear()
        overdueMessageIds.clear()
        val now = System.currentTimeMillis()
        entries.values.forEach { saved ->
            val data = saved.saveData
            channelIndex.getOrPut(data.channelId) { HashSet() }.add(data.messageId)
            if (saved.message == null) missingMessageIds.add(data.messageId)
            if (data.dueAt != null && data.dueAt.dateTimeMillis <= now) overdueMessageIds.add(data.messageId)
        }
        markChanged()
    }

    override fun snapshotData() {
        snapshot = State(Collections.unmodifiableMap(LinkedHashMap(entries)), loading, error)
        overdueCount = overdueMessageIds.size
    }

    private fun isCurrent(expectedGeneration: Long) = generation == expectedGeneration && accountId != null
    private fun key(channelId: Long, messageId: Long) = "$channelId-$messageId"
}
