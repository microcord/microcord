package com.discord.stores

import com.discord.api.message.PollVoteEvent
import com.discord.models.message.Message
import com.discord.stores.updates.ObservationDeck
import java.util.Collections
import rx.Observable

class StorePinnedMessages(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val loader: (Long, (Result<List<Message>>) -> Unit) -> Unit = { _, callback -> callback(Result.success(emptyList())) },
) : StoreV2() {
    private val pinnedMessages = HashMap<Long, MutableList<Message>>()
    private val loading = HashSet<Long>()
    private var snapshot: Map<Long, List<Message>> = emptyMap()

    fun observeForChannel(channelId: Long): Observable<List<Message>> {
        loadPinnedMessages(channelId)
        return observationDeck.connectRx(this) { snapshot[channelId].orEmpty() }.distinctUntilChanged()
    }

    fun observePinnedMessage(channelId: Long, messageId: Long): Observable<Message?> =
        observeForChannel(channelId).map { messages -> messages.firstOrNull { it.id == messageId } }.distinctUntilChanged()

    @JvmOverloads
    fun loadPinnedMessages(channelId: Long, force: Boolean = false) {
        if ((!force && channelId in snapshot) || !loading.add(channelId)) return
        loader(channelId) { result -> dispatcher.schedule {
            loading.remove(channelId)
            result.getOrNull()?.let {
                pinnedMessages[channelId] = it.toMutableList()
                markChanged()
            }
        } }
    }

    /**
     * `CHANNEL_PINS_UPDATE`: the pin set changed, so refetch it.
     *
     * Only the working map is cleared. `snapshot` is rebuilt exclusively by [snapshotData] at the
     * end of the dispatch; writing it from a handler would let an observer read a map that is
     * half this channel's old pins and half nothing, which is the torn state StoreV2's snapshot
     * confinement exists to prevent.
     *
     * [loadPinnedMessages] must be told to reload explicitly, because its own dedupe would see
     * the still-published snapshot and decide the channel was already loaded.
     */
    @StoreThread
    fun handlePinsUpdate(channelId: Long) {
        pinnedMessages.remove(channelId)
        markChanged()
        loadPinnedMessages(channelId, force = true)
    }

    @StoreThread
    fun handleMessageDeleteBulk(channelId: Long, messageIds: Collection<Long>) {
        val messages = pinnedMessages[channelId] ?: return
        if (messages.removeAll { it.id in messageIds }) markChanged()
    }

    @StoreThread
    fun handleMessageUpdate(channelId: Long, messageId: Long, pinned: Boolean, merge: (Message) -> Message) {
        val messages = pinnedMessages[channelId] ?: return
        val index = messages.indexOfFirst { it.id == messageId }
        when {
            index >= 0 && !pinned -> { messages.removeAt(index); markChanged() }
            index >= 0 -> { messages[index] = merge(messages[index]); markChanged() }
        }
    }

    @StoreThread
    fun handleMessageUpdate(message: com.discord.api.message.Message) {
        val pinned = message.pinned ?: return
        val messages = pinnedMessages[message.channelId] ?: return
        val index = messages.indexOfFirst { it.id == message.id }
        when {
            index >= 0 && !pinned -> { messages.removeAt(index); markChanged() }
            index >= 0 -> { messages[index] = messages[index].merge(message); markChanged() }
            pinned -> { messages += Message(message); messages.sortByDescending(Message::id); markChanged() }
        }
    }

    @StoreThread
    fun handlePollVote(event: PollVoteEvent, added: Boolean, meUserId: Long) {
        handleMessageUpdate(event.channelId, event.messageId, pinned = true) {
            it.withPollVote(event, added, meUserId)
        }
    }

    @StoreThread fun handlePreLogout() { if (pinnedMessages.isNotEmpty()) { pinnedMessages.clear(); markChanged() } }

    override fun snapshotData() {
        snapshot = Collections.unmodifiableMap(pinnedMessages.mapValues { (_, messages) ->
            Collections.unmodifiableList(messages.toList())
        })
    }
}
