package com.discord.stores

import com.discord.models.domain.ModelMessageDelete
import com.discord.models.message.Message
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.rx.filterNull
import rx.Observable

/**
 * Resolution of the messages that replies point at.
 *
 * A reply (`type == 19`) carries a `message_reference` and *sometimes* an inlined
 * `referenced_message`. Rendering the grey preview line above a reply therefore needs an answer
 * to "what is message X" for messages that may not be in the channel's loaded window at all — or
 * may have been deleted. That question is what this store answers, via [MessageState].
 *
 * It is deliberately separate from [StorePendingReplies], which tracks the reply *being
 * composed*; the two never interact.
 *
 * Only referenced messages are cached, and only the most recent [MAX_CACHED_MESSAGES] of them:
 * scrolling a busy channel would otherwise retain every quoted message for the session.
 */
class StoreMessageReplies @JvmOverloads constructor(
    private val dispatcher: Dispatcher,
    private val storeMessages: StoreMessages,
    private val observationDeck: ObservationDeck,
) : StoreV2() {

    /** What is known about a message some reply points at. */
    sealed class MessageState {
        /** The message existed and has since been deleted — render "original message deleted". */
        object Deleted : MessageState()

        /** The message is referenced but has never been seen — render the loading placeholder. */
        object Unloaded : MessageState()

        /** The message body is known and the preview can be rendered. */
        data class Loaded(val message: Message) : MessageState()
    }

    /**
     * An LRU bounded at [MAX_CACHED_MESSAGES] entries.
     *
     * `LinkedHashMap`'s insertion order is what makes the eviction sensible here: the oldest
     * *referenced* message is the one least likely to still be on screen.
     */
    private class MessageCache<K, V> : LinkedHashMap<K, V>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > MAX_CACHED_MESSAGES
    }

    private val repliedMessagesCache = MessageCache<Long, MessageState>()

    /** Channel id to the set of message ids referenced from that channel. */
    private val channelMap = HashMap<Long, Set<Long>>()

    private var repliedMessagesCacheSnapshot: Map<Long, MessageState> = emptyMap()
    private var repliedChannelMessagesCacheSnapshot: Map<Long, Map<Long, MessageState>> = emptyMap()

    fun getAllMessageReferences(): Map<Long, MessageState> = repliedMessagesCacheSnapshot

    /** The referenced-message states needed to render [channelId]'s currently loaded replies. */
    fun observeMessageReferencesForChannel(channelId: Long): Observable<Map<Long, MessageState>> =
        observationDeck.connectRx(this) { getCachedChannelMessages(channelId) }.distinctUntilChanged()

    /** Convenience for a single reply preview. */
    fun observeMessageReference(channelId: Long, messageId: Long): Observable<MessageState> =
        observeMessageReferencesForChannel(channelId)
            .map<MessageState?> { it[messageId] }
            .filterNull()
            .distinctUntilChanged()

    @StoreThread
    fun handleLoadMessages(messages: Collection<Message>) {
        // Every message is processed before deciding to publish: `any { }` would short-circuit and
        // leave the rest of the chunk unresolved.
        val changed = messages.map(::processMessage).contains(true)
        if (changed) markChanged()
    }

    @StoreThread
    fun handleMessageCreate(message: com.discord.api.message.Message) {
        if (processMessage(message)) markChanged()
    }

    @StoreThread
    fun handleMessageCreate(message: Message) {
        if (processMessage(message)) markChanged()
    }

    @StoreThread
    fun handleMessageUpdate(message: com.discord.api.message.Message) {
        if (!repliedMessagesCache.containsKey(message.id)) return
        val cached = repliedMessagesCache[message.id] as? MessageState.Loaded ?: return
        val merged = cached.message.merge(message)
        updateCache(merged.id, merged.channelId, MessageState.Loaded(merged))
        markChanged()
    }

    @StoreThread
    fun handleMessageDelete(messageDeleteBulk: ModelMessageDelete) {
        var changed = false
        messageDeleteBulk.messageIds.orEmpty().forEach { messageId ->
            if (deleteMessage(messageId, messageDeleteBulk.channelId)) changed = true
        }
        if (changed) markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        if (repliedMessagesCache.isEmpty() && channelMap.isEmpty()) return
        repliedMessagesCache.clear()
        channelMap.clear()
        markChanged()
    }

    private fun getCachedChannelMessages(channelId: Long): Map<Long, MessageState> =
        repliedChannelMessagesCacheSnapshot[channelId] ?: NO_RESULTS

    @StoreThread
    private fun deleteMessage(messageId: Long, channelId: Long): Boolean {
        if (!repliedMessagesCache.containsKey(messageId)) return false
        updateCache(messageId, channelId, MessageState.Deleted)
        return true
    }

    @StoreThread
    private fun processMessage(message: com.discord.api.message.Message): Boolean =
        processMessage(Message(message))

    /**
     * Records what [message] tells us about referenced messages.
     *
     * Two independent things can happen and both must be attempted:
     *
     * 1. [message] may itself be something a previously seen reply points at, in which case its
     *    cached state is upgraded to [MessageState.Loaded].
     * 2. [message] may be a reply, in which case its target is resolved — from the inlined
     *    `referencedMessage` if the server sent one, else from [StoreMessages], else recorded as
     *    [MessageState.Unloaded] so the preview renders a placeholder rather than nothing.
     */
    @StoreThread
    private fun processMessage(message: Message): Boolean {
        var changed = false
        if (repliedMessagesCache.containsKey(message.id)) {
            updateCache(message.id, message.channelId, MessageState.Loaded(message))
            changed = true
        }

        // An optimistic message is still typed TYPE_SENDING (-1) — not yet the server's TYPE_REPLY
        // (19) — but it already carries the reply's messageReference, and the replied-to message is
        // necessarily already loaded (the user just tapped reply on it). Resolve it now instead of
        // waiting for server confirmation, so the preview doesn't pop in only once that lands.
        val isLocalReply = message.type == TYPE_SENDING && message.messageReference != null
        if (!message.isEmbeddedMessageType() && !isLocalReply) return changed
        val reference = message.messageReference ?: return changed
        val referencedMessage = message.referencedMessage
        val referencedChannelId = reference.channelId
        val referencedMessageId = reference.messageId

        return when {
            referencedMessage != null -> {
                processReferencedMessage(Message(referencedMessage))
                true
            }

            referencedChannelId != null && referencedMessageId != null -> {
                val known = storeMessages.getMessage(referencedChannelId, referencedMessageId)
                if (known != null) {
                    processReferencedMessage(known)
                } else {
                    updateCache(referencedMessageId, referencedChannelId, MessageState.Unloaded)
                }
                true
            }

            else -> true
        }
    }

    /**
     * Caches a resolved reference, then recurses when the referenced message is itself a reply.
     *
     * The recursion is what lets a reply-to-a-reply render its own preview; it terminates because
     * only `type == 19` recurses and each hop moves to a strictly different message.
     */
    @StoreThread
    private fun processReferencedMessage(referencedMessage: Message) {
        updateCache(
            referencedMessage.id,
            referencedMessage.channelId,
            MessageState.Loaded(referencedMessage),
        )
        if (referencedMessage.type == MESSAGE_TYPE_REPLY) processMessage(referencedMessage)
    }

    @StoreThread
    private fun updateCache(messageId: Long, channelId: Long, messageState: MessageState) {
        repliedMessagesCache[messageId] = messageState
        channelMap[channelId] = channelMap[channelId].orEmpty() + messageId
    }

    override fun snapshotData() {
        snapshotChannelMessages()
        snapshotAllMessages()
    }

    @StoreThread
    private fun snapshotChannelMessages() {
        repliedChannelMessagesCacheSnapshot = channelMap.mapValues { (_, messageIds) ->
            messageIds.associateWith { repliedMessagesCache[it] ?: MessageState.Unloaded }
        }
    }

    private fun snapshotAllMessages() {
        repliedMessagesCacheSnapshot = HashMap(repliedMessagesCache)
    }

    companion object {
        /** Cache bound; reference `MessageCache.removeEldestEntry`. */
        const val MAX_CACHED_MESSAGES = 1_000

        /** `Message.type` for an inline reply. */
        private const val MESSAGE_TYPE_REPLY = 19

        /** `Message.type` for an optimistic message awaiting server confirmation. */
        private const val TYPE_SENDING = -1

        private val NO_RESULTS: Map<Long, MessageState> = emptyMap()
    }
}
