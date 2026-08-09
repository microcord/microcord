package com.discord.stores

import com.discord.api.message.PollVoteEvent
import com.discord.models.message.Message
import com.discord.stores.updates.ObservationDeck
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import rx.Observable

/**
 * Pinned messages, one page at a time.
 *
 * `GET /channels/{id}/messages/pins` is ordered newest-pinned first and pages backwards through
 * [Pin.pinnedAt], so a channel can hold more pins than one response carries. [Page.hasMore] is the
 * only end-of-list signal — a short page does not mean the end.
 */
class StorePinnedMessages(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val loader: (Long, String?, (Result<Page>) -> Unit) -> Unit =
        { _, _, callback -> callback(Result.success(Page(emptyList(), hasMore = false))) },
    private val nowIso: () -> String = {
        SimpleDateFormat(PINNED_AT_FORMAT, Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
    },
) : StoreV2() {

    /** [pinnedAt] is the ISO8601 the next page's `before` cursor is taken from. */
    data class Pin(val pinnedAt: String, val message: Message)

    data class Page(val items: List<Pin>, val hasMore: Boolean)

    data class ChannelPins(
        val items: List<Pin> = emptyList(),
        val hasMore: Boolean = false,
        val isLoading: Boolean = false,
        /** No request has settled yet, so an empty list must not render as "no pins". */
        val isInitialLoad: Boolean = true,
    ) {
        val messages: List<Message> get() = items.map(Pin::message)
    }

    private val pins = HashMap<Long, ChannelPins>()
    private var snapshot: Map<Long, ChannelPins> = emptyMap()

    fun observeForChannel(channelId: Long): Observable<List<Message>> =
        observeChannelPins(channelId).map(ChannelPins::messages).distinctUntilChanged()

    fun observeChannelPins(channelId: Long): Observable<ChannelPins> {
        loadPinnedMessages(channelId)
        return observationDeck.connectRx(this) { snapshot[channelId] ?: ChannelPins() }.distinctUntilChanged()
    }

    fun observePinnedMessage(channelId: Long, messageId: Long): Observable<Message?> =
        observeForChannel(channelId).map { messages -> messages.firstOrNull { it.id == messageId } }.distinctUntilChanged()

    @JvmOverloads
    fun loadPinnedMessages(channelId: Long, force: Boolean = false) {
        val current = snapshot[channelId]
        if (current?.isLoading == true) return
        if (!force && current != null && !current.isInitialLoad) return
        fetch(channelId, before = null)
    }

    /**
     * Requests the page after the one currently held, keyed on the tail's [Pin.pinnedAt]. Ignored
     * while a request is in flight or once the server has said this is the last page.
     */
    fun loadMore(channelId: Long) {
        val current = snapshot[channelId] ?: return
        if (current.isLoading || !current.hasMore) return
        val cursor = current.items.lastOrNull()?.pinnedAt ?: return
        fetch(channelId, before = cursor)
    }

    private fun fetch(channelId: Long, before: String?) {
        dispatcher.schedule { markLoading(channelId) }
        loader(channelId, before) { result ->
            dispatcher.schedule {
                val page = result.getOrNull()
                val existing = pins[channelId] ?: ChannelPins()
                pins[channelId] = if (page == null) {
                    // A transport failure leaves the channel unloaded rather than empty, so the
                    // next observer retries instead of rendering "no pins".
                    existing.copy(isLoading = false)
                } else {
                    ChannelPins(
                        // Appended, not merged: the server owns the order and pages never overlap.
                        items = if (before == null) page.items else existing.items + page.items,
                        hasMore = page.hasMore,
                        isLoading = false,
                        isInitialLoad = false,
                    )
                }
                markChanged()
            }
        }
    }

    @StoreThread
    private fun markLoading(channelId: Long) {
        pins[channelId] = (pins[channelId] ?: ChannelPins()).copy(isLoading = true)
        markChanged()
    }

    /**
     * `CHANNEL_PINS_UPDATE`: the pin set changed, so refetch from the first page.
     *
     * Only the working map is cleared. `snapshot` is rebuilt exclusively by [snapshotData] at the
     * end of the dispatch; writing it from a handler would let an observer read a map that is
     * half this channel's old pins and half nothing, which is the torn state StoreV2's snapshot
     * confinement exists to prevent.
     */
    @StoreThread
    fun handlePinsUpdate(channelId: Long) {
        pins.remove(channelId)
        markChanged()
        fetch(channelId, before = null)
    }

    @StoreThread
    fun handleMessageDeleteBulk(channelId: Long, messageIds: Collection<Long>) {
        update(channelId) { items -> items.filterNot { it.message.id in messageIds } }
    }

    @StoreThread
    fun handleMessageUpdate(channelId: Long, messageId: Long, pinned: Boolean, merge: (Message) -> Message) {
        update(channelId) { items ->
            val index = items.indexOfFirst { it.message.id == messageId }
            when {
                index < 0 -> items
                !pinned -> items.filterIndexed { position, _ -> position != index }
                else -> items.toMutableList().apply {
                    this[index] = this[index].copy(message = merge(this[index].message))
                }
            }
        }
    }

    /**
     * The reference tracks pin membership from `MESSAGE_UPDATE` rather than `CHANNEL_PINS_UPDATE`,
     * so a newly pinned message appears without a refetch. It carries no `pinned_at`, so the local
     * clock stands in until the next fetch replaces it.
     */
    @StoreThread
    fun handleMessageUpdate(message: com.discord.api.message.Message) {
        val pinned = message.pinned ?: return
        update(message.channelId) { items ->
            val index = items.indexOfFirst { it.message.id == message.id }
            when {
                index >= 0 && !pinned -> items.filterIndexed { position, _ -> position != index }
                index >= 0 -> items.toMutableList().apply {
                    this[index] = this[index].copy(message = this[index].message.merge(message))
                }
                pinned -> listOf(Pin(nowIso(), Message(message))) + items
                else -> items
            }
        }
    }

    @StoreThread
    fun handlePollVote(event: PollVoteEvent, added: Boolean, meUserId: Long) {
        handleMessageUpdate(event.channelId, event.messageId, pinned = true) {
            it.withPollVote(event, added, meUserId)
        }
    }

    @StoreThread
    private fun update(channelId: Long, transform: (List<Pin>) -> List<Pin>) {
        val current = pins[channelId] ?: return
        val updated = transform(current.items)
        if (updated != current.items) {
            pins[channelId] = current.copy(items = updated)
            markChanged()
        }
    }

    @StoreThread fun handlePreLogout() { if (pins.isNotEmpty()) { pins.clear(); markChanged() } }

    private companion object {
        const val PINNED_AT_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
    }

    override fun snapshotData() {
        snapshot = Collections.unmodifiableMap(
            pins.mapValues { (_, channel) ->
                channel.copy(items = Collections.unmodifiableList(channel.items.toList()))
            },
        )
    }
}
