package com.discord.stores

import com.discord.api.message.Message as ApiMessage
import com.discord.models.message.Message
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable
import java.util.Collections

/**
 * The newest preloaded message per DM channel, mirroring the RN client's `MessagePreviewStore`.
 *
 * Kept deliberately separate from [StoreMessages]: a preview is not an authoritative window of
 * history, so it must never detach, trim, persist, or evict anything. The RN client does exactly
 * this — the `MESSAGE_PREVIEWS_LOADED` payload goes into its own cache, and only real loads
 * touch the message table.
 *
 * Entries are versioned by [generation]; [put] ignores writes for a channel that already holds a
 * message from the same or newer generation, matching RN's `isLatest` guard so a stale reply
 * cannot clobber a newer gateway message.
 */
class StoreMessagePreviews(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {
    private data class Entry(val message: Message, val generation: Long)

    private val previews = HashMap<Long, Entry>()
    private var snapshot: Map<Long, Message> = emptyMap()

    fun get(channelId: Long): Message? = snapshot[channelId]

    fun observePreviews(): Observable<Map<Long, Message>> =
        observationDeck.connectRx(this, generator = ::snapshotPreviews).distinctUntilChanged()

    private fun snapshotPreviews(): Map<Long, Message> = snapshot

    @StoreThread
    fun handlePreviewsLoaded(messages: List<ApiMessage>, generation: Long) {
        var changed = false
        messages.forEach { message ->
            val current = previews[message.channelId]
            if (current == null || current.generation < generation) {
                previews[message.channelId] = Entry(Message(message), generation)
                changed = true
            }
        }
        if (changed) markChanged()
    }

    /** A real gateway message always outranks any preloaded preview for its channel. */
    @StoreThread
    fun handleMessageCreate(message: ApiMessage) {
        val removed = previews.remove(message.channelId)
        if (removed != null) markChanged()
    }

    @StoreThread
    fun handleChannelOrThreadDelete(channelId: Long) {
        if (previews.remove(channelId) != null) markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        if (previews.isNotEmpty()) {
            previews.clear()
            markChanged()
        }
    }

    override fun snapshotData() {
        snapshot = Collections.unmodifiableMap(
            previews.mapValues { (_, entry) -> entry.message },
        )
    }
}
