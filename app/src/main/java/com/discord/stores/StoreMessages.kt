package com.discord.stores

import com.discord.models.message.Message
import com.discord.models.domain.ModelMessageDelete
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.util.Collections
import java.util.LinkedHashMap
import java.util.TreeMap
import rx.Observable
import rx.subjects.BehaviorSubject
import com.discord.api.message.reaction.MessageReactionUpdate
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.api.message.reaction.MessageReaction

class StoreMessagesHolder(
    private val cache: Persister<Map<Long, List<Message>>> = Persister(STORE_MESSAGES_CACHE_V38, emptyMap()),
) {
    private val messages = LinkedHashMap<Long, TreeMap<Long, Message>>()
    private val activeChannels = LinkedHashSet<Long>()
    private val detachedChannels = LinkedHashSet<Long>()
    private val staleMessages = HashSet<Long>()
    private val nonceIds = HashMap<Pair<Long, String>, Long>()
    private var selectedChannelId = 0L
    private var snapshot: Map<Long, List<Message>> = emptyMap()
    private val messagesSubject = BehaviorSubject.create(snapshot)
    private val detachedSubject = BehaviorSubject.create<Set<Long>>(emptySet())

    @Synchronized
    fun restore(enabled: Boolean = true) {
        messages.clear()
        staleMessages.clear()
        if (enabled) cache.get().forEach { (channelId, cached) ->
            messages[channelId] = TreeMap<Long, Message>().apply {
                cached.forEach { message ->
                    put(message.id, message)
                    staleMessages += message.id
                }
            }
        }
        publish()
    }

    @Synchronized
    fun selectChannel(channelId: Long) {
        selectedChannelId = channelId
        messages.remove(channelId)?.let { messages[channelId] = it }
            ?: run { messages[channelId] = TreeMap() }
        activeChannels += channelId
        publish()
    }

    @Synchronized
    fun addMessages(messages: List<Message>) = messages.forEach { message ->
        addMessage(message.channelId, message, message.nonce, message.isLocal())
    }

    @Synchronized
    fun addMessage(channelId: Long, message: Message, nonce: String? = null, isLocal: Boolean = false) {
        if (channelId !in activeChannels) return
        val channelMessages = messages.getOrPut(channelId) { TreeMap() }
        if (nonce != null) {
            val key = channelId to nonce
            if (isLocal) nonceIds[key] = message.id
            else nonceIds.remove(key)?.let(channelMessages::remove)
        }
        channelMessages[message.id] = message
        trim(channelId, channelMessages, keepOldest = false, detach = false)
        touch(channelId)
        publish()
    }

    @Synchronized
    fun updateMessage(channelId: Long, messageId: Long, update: (Message) -> Message) {
        if (channelId in detachedChannels) return
        val channelMessages = messages[channelId] ?: return
        val current = channelMessages[messageId] ?: return
        channelMessages[messageId] = update(current)
        touch(channelId)
        publish()
    }

    @Synchronized
    fun deleteMessages(channelId: Long, messageIds: Collection<Long>) {
        val channelMessages = messages[channelId] ?: return
        if (messageIds.any { channelMessages.remove(it) != null }) {
            nonceIds.entries.removeAll { it.key.first == channelId && it.value in messageIds }
            touch(channelId)
            publish()
        }
    }

    @Synchronized
    fun loadMessageChunks(chunks: List<StoreMessagesLoader.ChannelChunk>) {
        chunks.forEach { chunk ->
            if (chunk.channelId !in activeChannels) return@forEach
            val channelMessages = messages.getOrPut(chunk.channelId) { TreeMap() }
            if (chunk.isInitial || chunk.isJump) {
                val removedIds = channelMessages.keys.filter { chunk.isJump || it in staleMessages }
                removedIds.forEach(channelMessages::remove)
                staleMessages.removeAll(removedIds.toSet())
            }
            chunk.messages.forEach { channelMessages[it.id] = it }
            if (chunk.isJump) setDetached(chunk.channelId, true)
            trim(chunk.channelId, channelMessages, chunk.isAppendingTop, chunk.isAppendingTop || chunk.isJump)
            if (chunk.isPresent && !chunk.isJump) setDetached(chunk.channelId, false)
            touch(chunk.channelId)
        }
        publish()
    }

    @Synchronized
    fun invalidate() {
        messages.values.forEach { staleMessages += it.keys }
        activeChannels.clear()
        if (selectedChannelId > 0) activeChannels += selectedChannelId
    }

    @Synchronized
    fun clear() {
        messages.clear()
        activeChannels.clear()
        detachedChannels.clear()
        staleMessages.clear()
        nonceIds.clear()
        cache.clear(persist = true)
        publish()
    }

    @Synchronized fun getMessages(channelId: Long): List<Message> = snapshot[channelId].orEmpty()
    @Synchronized fun getAll(): Map<Long, List<Message>> = snapshot
    @Synchronized fun getActiveChannels(): Set<Long> = activeChannels.toSet()
    @Synchronized fun getDetached(): Set<Long> = detachedChannels.toSet()
    @Synchronized fun getStaleMessageIds(): Set<Long> = staleMessages.toSet()
    fun observeMessages(): Observable<Map<Long, List<Message>>> = messagesSubject.distinctUntilChanged()
    fun observeDetachedChannels(): Observable<Set<Long>> = detachedSubject.distinctUntilChanged()

    private fun trim(channelId: Long, channelMessages: TreeMap<Long, Message>, keepOldest: Boolean, detach: Boolean) {
        if (channelMessages.size < MAX_MESSAGES_PER_CHANNEL) return
        val entries = channelMessages.entries.toList()
        val retained = if (keepOldest) entries.take(TRIMMED_MESSAGES_PER_CHANNEL)
        else entries.takeLast(TRIMMED_MESSAGES_PER_CHANNEL)
        channelMessages.clear()
        retained.forEach { channelMessages[it.key] = it.value }
        if (detach) setDetached(channelId, true)
    }

    private fun touch(channelId: Long) {
        messages.remove(channelId)?.let { messages[channelId] = it }
    }

    private fun setDetached(channelId: Long, detached: Boolean) {
        val changed = if (detached) detachedChannels.add(channelId) else detachedChannels.remove(channelId)
        if (changed) detachedSubject.onNext(Collections.unmodifiableSet(detachedChannels.toSet()))
    }

    private fun publish() {
        snapshot = Collections.unmodifiableMap(messages.mapValues { (_, value) ->
            Collections.unmodifiableList(value.values.toList())
        })
        messagesSubject.onNext(snapshot)
        cache.set(computeCache())
    }

    private fun computeCache(): Map<Long, List<Message>> = LinkedHashMap<Long, List<Message>>().apply {
        messages.entries.toList().takeLast(CACHE_MAX_CHANNELS).forEach { (channelId, channelMessages) ->
            if (channelMessages.isNotEmpty()) {
                val count = if (channelId == selectedChannelId) CACHE_SELECTED_MESSAGES else CACHE_OTHER_MESSAGES
                put(channelId, channelMessages.values.toList().takeLast(count))
            }
        }
    }

    companion object {
        const val STORE_MESSAGES_CACHE_V38 = "STORE_MESSAGES_CACHE_V38"
        const val MAX_MESSAGES_PER_CHANNEL = 200
        const val TRIMMED_MESSAGES_PER_CHANNEL = 100
        private const val CACHE_MAX_CHANNELS = 8
        private const val CACHE_SELECTED_MESSAGES = 20
        private const val CACHE_OTHER_MESSAGES = 10
    }
}

class StoreLocalMessagesHolder(
    private val cache: Persister<Map<Long, List<LocalEntry>>> = Persister(STORE_LOCAL_MESSAGES_CACHE_V11, emptyMap()),
) {
    data class LocalEntry(val nonce: String, val message: Message)

    private val messages = HashMap<Long, TreeMap<Long, LocalEntry>>()
    private var snapshot: Map<Long, List<Message>> = emptyMap()
    private val subject = BehaviorSubject.create(snapshot)

    @Synchronized
    fun restore(enabled: Boolean = true) {
        messages.clear()
        if (enabled) cache.get().forEach { (channelId, entries) ->
            messages[channelId] = TreeMap<Long, LocalEntry>().apply { entries.forEach { put(it.message.id, it) } }
        }
        publish()
    }

    @Synchronized
    fun addMessage(message: Message) = addMessage(
        message.channelId,
        requireNotNull(message.nonce),
        message,
    )

    @Synchronized
    fun addMessage(channelId: Long, nonce: String, message: Message) {
        val channelMessages = messages.getOrPut(channelId) { TreeMap() }
        channelMessages.entries.removeAll { it.value.nonce == nonce }
        channelMessages[message.id] = LocalEntry(nonce, message)
        publish()
    }

    @Synchronized fun getMessage(channelId: Long, nonce: String): Message? =
        messages[channelId]?.values?.firstOrNull { it.nonce == nonce }?.message

    @Synchronized
    fun deleteMessage(channelId: Long, nonce: String): Message? {
        val channelMessages = messages[channelId] ?: return null
        val entry = channelMessages.entries.firstOrNull { it.value.nonce == nonce } ?: return null
        channelMessages.remove(entry.key)
        if (channelMessages.isEmpty()) messages.remove(channelId)
        publish()
        return entry.value.message
    }

    @Synchronized
    fun deleteMessage(channelId: Long, messageId: Long) {
        val channelMessages = messages[channelId] ?: return
        if (channelMessages.remove(messageId) == null) return
        if (channelMessages.isEmpty()) messages.remove(channelId)
        publish()
    }

    @Synchronized
    fun clearCache() {
        messages.clear()
        cache.clear(persist = true)
        publish()
    }

    @Synchronized fun getMessages(channelId: Long): List<Message> = snapshot[channelId].orEmpty()
    @Synchronized fun getAll(): Map<Long, List<Message>> = snapshot
    @Synchronized fun getFlattenedMessages(): List<Message> = snapshot.values.flatten()
    fun observeMessages(): Observable<Map<Long, List<Message>>> = subject.distinctUntilChanged()

    private fun publish() {
        snapshot = Collections.unmodifiableMap(messages.mapValues { (_, channelMessages) ->
            Collections.unmodifiableList(channelMessages.values.map(LocalEntry::message))
        })
        subject.onNext(snapshot)
        cache.set(messages.mapValues { it.value.values.toList() })
    }

    companion object { const val STORE_LOCAL_MESSAGES_CACHE_V11 = "STORE_LOCAL_MESSAGES_CACHE_V11" }
}

class StoreMessages(
    private val observationDeck: ObservationDeck,
    val holder: StoreMessagesHolder = StoreMessagesHolder(),
    val localMessagesHolder: StoreLocalMessagesHolder = StoreLocalMessagesHolder(),
) : StoreV2() {
    private var messagesSnapshot: Map<Long, List<Message>> = emptyMap()

    @StoreThread
    fun restore() {
        holder.restore()
        localMessagesHolder.restore()
        markChanged()
    }

    @StoreThread
    fun handleChannelSelected(channelId: Long) {
        holder.selectChannel(channelId)
        markChanged()
    }

    @StoreThread fun handleConnected(connected: Boolean) { if (!connected) holder.invalidate() }

    @StoreThread
    fun handleMessageCreate(message: Message) = handleMessageCreate(
        message.channelId, message, message.nonce,
    )

    @StoreThread
    fun handleMessageCreate(channelId: Long, message: Message, nonce: String? = null) {
        nonce?.let { localMessagesHolder.deleteMessage(channelId, it) }
        holder.addMessage(channelId, message, nonce)
        markChanged()
    }

    @StoreThread
    fun handleMessageCreate(messages: List<com.discord.api.message.Message>) {
        messages.forEach { apiMessage -> handleMessageCreate(apiMessage.channelId, Message(apiMessage), apiMessage.nonce) }
    }

    @StoreThread
    fun handleLocalMessageCreate(channelId: Long, nonce: String, message: Message) {
        localMessagesHolder.addMessage(channelId, nonce, message)
        markChanged()
    }

    @StoreThread
    fun handleLocalMessageDelete(channelId: Long, nonce: String) {
        if (localMessagesHolder.deleteMessage(channelId, nonce) != null) markChanged()
    }

    @StoreThread
    fun handleMessageUpdate(channelId: Long, messageId: Long, merge: (Message) -> Message) {
        holder.updateMessage(channelId, messageId, merge)
        markChanged()
    }

    @StoreThread
    fun handleMessageUpdate(message: com.discord.api.message.Message) =
        handleMessageUpdate(message.channelId, message.id) { current -> current.merge(message) }

    @StoreThread
    fun handleReaction(update: MessageReactionUpdate, added: Boolean, meUserId: Long) {
        handleMessageUpdate(update.channelId, update.messageId) { message ->
            val reactions = message.reactions.orEmpty().toMutableList()
            val index = reactions.indexOfFirst { sameEmoji(it.emoji, update.emoji) }
            if (added) {
                if (index >= 0) reactions[index] = reactions[index].copy(
                    count = reactions[index].count + 1,
                    me = reactions[index].me || update.userId == meUserId,
                ) else reactions += MessageReaction(1, update.emoji, update.userId == meUserId)
            } else if (index >= 0) {
                val current = reactions[index]
                if (current.count <= 1) reactions.removeAt(index) else reactions[index] = current.copy(
                    count = current.count - 1,
                    me = if (update.userId == meUserId) false else current.me,
                )
            }
            message.copy(reactions = reactions)
        }
    }

    @StoreThread
    fun handleReactionRemoveAll(channelId: Long, messageId: Long) =
        handleMessageUpdate(channelId, messageId) { it.copy(reactions = emptyList()) }

    @StoreThread
    fun handleReactionRemoveEmoji(channelId: Long, messageId: Long, emoji: MessageReactionEmoji) =
        handleMessageUpdate(channelId, messageId) { message ->
            message.copy(reactions = message.reactions.orEmpty().filterNot { sameEmoji(it.emoji, emoji) })
        }

    @StoreThread fun handleMessageDelete(channelId: Long, messageId: Long) = handleMessageDeleteBulk(channelId, listOf(messageId))

    @StoreThread
    fun handleMessageDeleteBulk(channelId: Long, messageIds: Collection<Long>) {
        holder.deleteMessages(channelId, messageIds)
        messageIds.forEach { localMessagesHolder.deleteMessage(channelId, it) }
        markChanged()
    }

    @StoreThread
    fun handleMessageDelete(messageDelete: ModelMessageDelete) =
        handleMessageDeleteBulk(messageDelete.channelId, messageDelete.messageIds.orEmpty())

    @StoreThread
    fun handleMessagesLoaded(chunk: StoreMessagesLoader.ChannelChunk) {
        holder.loadMessageChunks(listOf(chunk))
        markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        holder.clear()
        localMessagesHolder.clearCache()
        markChanged()
    }

    fun getMessages(channelId: Long): List<Message> = messagesSnapshot[channelId].orEmpty()
    fun getMessage(channelId: Long, messageId: Long): Message? = getMessages(channelId).firstOrNull { it.id == messageId }
    fun observeMessagesForChannel(channelId: Long): Observable<List<Message>> =
        observationDeck.connectRx(this) { getMessages(channelId) }.distinctUntilChanged()
    fun observeMessage(channelId: Long, messageId: Long): Observable<Message?> =
        observeMessagesForChannel(channelId).map { messages -> messages.firstOrNull { it.id == messageId } }.distinctUntilChanged()
    fun observeIsDetached(channelId: Long): Observable<Boolean> =
        holder.observeDetachedChannels().map { channelId in it }.distinctUntilChanged()

    override fun snapshotData() {
        val detached = holder.getDetached()
        val channels = holder.getAll().keys + localMessagesHolder.getAll().keys
        messagesSnapshot = Collections.unmodifiableMap(channels.associateWith { channelId ->
            val synced = holder.getMessages(channelId)
            if (channelId in detached) synced else Collections.unmodifiableList(
                (synced + localMessagesHolder.getMessages(channelId)).sortedBy(Message::id),
            )
        })
    }

    private fun sameEmoji(left: MessageReactionEmoji, right: MessageReactionEmoji) =
        if (left.id != null || right.id != null) left.id == right.id else left.name == right.name
}
