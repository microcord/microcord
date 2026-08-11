package com.discord.stores

import android.content.ContentResolver
import android.util.Log
import com.discord.api.activity.Activity
import com.discord.api.application.Application
import com.discord.api.message.MessageReference
import com.discord.api.message.PollAnswerCount
import com.discord.api.message.PollResults
import com.discord.api.message.PollVoteEvent
import com.discord.api.interaction.InteractionStateUpdate
import com.discord.api.message.activity.MessageActivity
import com.discord.api.message.allowedmentions.MessageAllowedMentions
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.api.message.reaction.MessageReactionUpdate
import com.discord.api.sticker.Sticker
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.commands.ApplicationCommandLocalSendData
import com.discord.models.domain.ModelMessageDelete
import com.discord.models.domain.ModelPayload
import com.discord.models.message.Message
import com.discord.models.user.User
import com.discord.restapi.RestAPIParams
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.attachments.UploadAttachment
import com.discord.utilities.attachments.toUploadAttachment
import com.discord.utilities.captcha.CaptchaSolution
import com.discord.utilities.messagesend.MessageResult
import com.discord.utilities.messagesend.MessageSendCoordinator
import com.discord.utilities.messagesend.MessageSendLocation
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import com.discord.utilities.user.UserUtils.synthesizeApiUser
import java.util.Collections
import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject

/**
 * The synced message history: everything the server has confirmed, per channel.
 *
 * Three ideas explain almost every method here.
 *
 * **Active channels.** Only channels the user has actually opened are stored. Everything else is
 * dropped on arrival, which is what keeps a client in 100 guilds from accumulating every message
 * it is subscribed to. [invalidate] resets that set on disconnect so a reconnect re-fetches
 * rather than merging into a stale window.
 *
 * **Detachment.** A channel is *detached* when the visible window is not the live tail — after
 * jumping to an old message, or after trimming away the newest messages while paging upwards.
 * A detached channel must ignore incoming `MESSAGE_CREATE`s: appending them would splice live
 * messages onto a historical window and produce a list with a hole in it. [updateDetachedState]
 * is the single place that decides this, because trimming and detaching are the same event.
 *
 * **Incremental publishing.** [updatedChannels] records which channels changed so [publishIfUpdated]
 * rebuilds only those entries, and [messageCacheTryPersist] throttles the disk write to once per
 * [CACHE_PERSIST_INTERVAL] with an equality check on top. Publishing is per-mutation; persisting
 * is not.
 */
class StoreMessagesHolder @JvmOverloads constructor(
    private val cache: Persister<Map<Long, List<Message>>> = Persister(STORE_MESSAGES_CACHE_V38, emptyMap()),
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val persistScheduler: Scheduler = Schedulers.computation(),
) {
    private val messages = LinkedHashMap<Long, TreeMap<Long, Message>>()
    private val activeChannels = LinkedHashSet<Long>()
    private val detachedChannels = LinkedHashSet<Long>()
    private val staleMessages = HashSet<Long>()

    /**
     * Nonce to the synthetic id of its optimistic message, so the server echo can evict it.
     *
     * The reference keys this by nonce alone; the channel is included here because a nonce is only
     * guaranteed unique within one client run and a collision would evict a message from the
     * wrong channel.
     */
    private val nonceIds = HashMap<Pair<Long, String>, Long>()

    /** Channels whose message list changed since the last publish. */
    private val updatedChannels = HashSet<Long>()

    private var selectedChannelId = 0L
    private var myUserId = 0L

    private var cacheEnabled = false
    private var cacheSnapshot: Map<Long, List<Message>> = emptyMap()
    private var cachePersistedAt = clock.currentTimeMillis()
    private var cachePersistSubscription: Subscription? = null

    private var snapshot: Map<Long, List<Message>> = emptyMap()
    private val messagesSubject = BehaviorSubject.create(snapshot).toSerialized()
    private val detachedSubject = BehaviorSubject.create<Set<Long>>(emptySet()).toSerialized()

    /**
     * Restores the persisted history.
     *
     * Restored messages are marked stale: they are shown immediately so the channel is not blank,
     * but the first real load replaces them wholesale rather than merging, because there is no way
     * to tell what was edited or deleted while the app was closed.
     *
     * [enabled] both gates reading *and* is remembered as [cacheEnabled], so a holder restored
     * with the cache off also never writes to it.
     */
    @Synchronized
    @JvmOverloads
    fun restore(enabled: Boolean = true) {
        messages.clear()
        staleMessages.clear()
        if (enabled) {
            cache.get().forEach { (channelId, cached) ->
                messages[channelId] = TreeMap<Long, Message>().apply {
                    cached.forEach { message ->
                        put(message.id, message)
                        staleMessages += message.id
                    }
                }
                updatedChannels += channelId
            }
        }
        cacheEnabled = enabled
        publishIfUpdated(force = true)
    }

    /** Identity of the current user, used to decide whether a reaction update is "mine". */
    @Synchronized
    fun setMyUserId(myUserId: Long) {
        this.myUserId = myUserId
    }

    /**
     * Marks [channelId] as the open channel.
     *
     * Re-inserting the entry moves it to the end of the [LinkedHashMap], which is what makes the
     * cache keep the most recently visited channels: [computeMessagesCache] walks from the end.
     */
    @Synchronized
    fun selectChannel(channelId: Long) {
        selectedChannelId = channelId
        val existing = messages.remove(channelId)
        messages[channelId] = existing ?: TreeMap()
        activeChannels += channelId
    }

    @Synchronized
    fun addMessages(messages: List<Message>) {
        messages.forEach { message ->
            val channelId = message.channelId
            // A detached channel is showing history; live messages must not be spliced onto it.
            if (!isChannelActive(channelId) || isChannelDetached(channelId)) return@forEach
            val channelMessages = this.messages.getOrPut(channelId) { TreeMap() }
            val nonce = message.nonce

            var removedPendingMessage = false
            if (nonce != null) {
                val key = channelId to nonce
                if (message.isLocal()) {
                    nonceIds[key] = message.id
                } else {
                    nonceIds.remove(key)?.let { pendingId ->
                        if (channelMessages.remove(pendingId) != null) removedPendingMessage = true
                    }
                }
            }

            channelMessages[message.id] = message
            // Replacing an optimistic message is not itself a change worth republishing unless the
            // insert also trimmed the window.
            if (updateDetachedState(channelId, channelMessages, isAppendingTop = false, isPresent = true, isJump = false) ||
                !removedPendingMessage
            ) {
                updatedChannels += channelId
            }
        }
        publishIfUpdated()
    }

    @Synchronized
    @JvmOverloads
    fun addMessage(channelId: Long, message: Message, nonce: String? = null, isLocal: Boolean = false) =
        addMessages(listOf(message.copy(channelId = channelId, nonce = nonce ?: message.nonce)))

    @Synchronized
    fun updateMessage(channelId: Long, messageId: Long, update: (Message) -> Message) {
        if (isChannelActive(channelId) && !isChannelDetached(channelId)) {
            val channelMessages = messages[channelId]
            val current = channelMessages?.get(messageId)
            if (current != null) {
                channelMessages[messageId] = update(current)
                updatedChannels += channelId
            }
        }
        publishIfUpdated()
    }

    @Synchronized
    fun updateMessages(message: com.discord.api.message.Message) =
        updateMessage(message.channelId, message.id) { it.merge(message) }

    @Synchronized
    fun deleteMessages(channelId: Long, messageIds: Collection<Long>) {
        if (!isChannelActive(channelId) || messageIds.isEmpty()) return
        val channelMessages = messages[channelId] ?: return
        messageIds.forEach { messageId ->
            if (channelMessages.remove(messageId) != null) updatedChannels += channelId
        }
        nonceIds.entries.removeAll { it.key.first == channelId && it.value in messageIds }
        publishIfUpdated()
    }

    /**
     * Merges loaded history into a channel.
     *
     * An initial load or a jump first evicts stale and out-of-window messages, because the chunk
     * is authoritative for its range; a paging load merges. Detachment is then re-evaluated once,
     * for the same reason [updateDetachedState] exists.
     */
    @Synchronized
    fun loadMessageChunks(chunks: List<StoreMessagesLoader.ChannelChunk>) {
        chunks.forEach { chunk ->
            if (!isChannelActive(chunk.channelId)) return@forEach
            val channelMessages = messages.getOrPut(chunk.channelId) { TreeMap() }
            val detached = isChannelDetached(chunk.channelId)

            if (chunk.isInitial || chunk.isJump) {
                val removedIds = channelMessages.keys.filter { it in staleMessages || detached || chunk.isJump }
                removedIds.forEach(channelMessages::remove)
                staleMessages.removeAll(removedIds.toSet())
            }

            chunk.messages.forEach { channelMessages[it.id] = it }
            updateDetachedState(chunk.channelId, channelMessages, chunk.isAppendingTop, chunk.isPresent, chunk.isJump)
            updatedChannels += chunk.channelId
        }
        publishIfUpdated()
    }

    /** Applies a batch of reaction adds or removes. */
    @Synchronized
    fun updateReactions(updates: List<MessageReactionUpdate>, add: Boolean) {
        updates.forEach { update ->
            if (!isChannelActive(update.channelId)) return@forEach
            val channelMessages = messages[update.channelId] ?: return@forEach
            val message = channelMessages[update.messageId] ?: return@forEach
            val isMe = update.userId == myUserId
            channelMessages[update.messageId] =
                if (add) addReaction(message, update.emoji, isMe) else removeReaction(message, update.emoji, isMe)
            updatedChannels += update.channelId
        }
        publishIfUpdated()
    }

    @Synchronized
    fun removeAllReactions(channelId: Long, messageId: Long) {
        if (!isChannelActive(channelId)) return
        val channelMessages = messages[channelId] ?: return
        val message = channelMessages[messageId] ?: return
        channelMessages[messageId] = message.copy(reactions = null)
        updatedChannels += channelId
        publishIfUpdated()
    }

    @Synchronized
    fun removeEmojiReactions(channelId: Long, messageId: Long, emoji: MessageReactionEmoji) {
        if (!isChannelActive(channelId)) return
        val channelMessages = messages[channelId] ?: return
        val message = channelMessages[messageId] ?: return
        val reactions = message.getReactionsMap()
        val key = emoji.reactionKey
        if (key !in reactions) return
        channelMessages[messageId] = withReactions(message, reactions.filterKeys { it != key })
        updatedChannels += channelId
        publishIfUpdated()
    }

    /**
     * Marks everything stale and narrows the active set to the open channel.
     *
     * Called on disconnect: anything the client believes about a channel it is no longer watching
     * is unverifiable, and re-opening it must re-fetch.
     */
    @Synchronized
    fun invalidate() {
        messages.values.forEach { staleMessages += it.keys }
        activeChannels.clear()
        if (selectedChannelId > 0) activeChannels += selectedChannelId
    }

    /**
     * Wipes history and the persisted cache.
     *
     * **Deliberate divergence.** The reference's `handlePreLogout` clears only the local
     * (unsent) messages and leaves `STORE_MESSAGES_CACHE_V38` on disk across a logout. This
     * project's security contract does not allow one account's message history to survive into
     * the next session, so the synced cache is cleared too.
     */
    @Synchronized
    fun clear() {
        messages.clear()
        activeChannels.clear()
        detachedChannels.clear()
        staleMessages.clear()
        nonceIds.clear()
        updatedChannels.clear()
        cachePersistSubscription?.unsubscribe()
        cachePersistSubscription = null
        cacheSnapshot = emptyMap()
        cache.clear(persist = true)
        snapshot = emptyMap()
        messagesSubject.onNext(snapshot)
        detachedSubject.onNext(emptySet())
    }

    @Synchronized fun getMessages(channelId: Long): List<Message> = snapshot[channelId].orEmpty()
    @Synchronized fun getAll(): Map<Long, List<Message>> = snapshot

    /** The live [TreeMap] for a channel. Store-thread only; callers must not retain it. */
    @Synchronized fun getMessagesForChannel(channelId: Long): TreeMap<Long, Message>? = messages[channelId]

    @Synchronized fun getActiveChannels(): Set<Long> = activeChannels.toSet()
    @Synchronized fun getDetached(): Set<Long> = detachedChannels.toSet()
    @Synchronized fun getStaleMessageIds(): Set<Long> = staleMessages.toSet()

    fun observeMessages(): Observable<Map<Long, List<Message>>> = messagesSubject.distinctUntilChanged()
    fun observeDetachedChannels(): Observable<Set<Long>> = detachedSubject.distinctUntilChanged()

    private fun isChannelActive(channelId: Long) = channelId in activeChannels
    private fun isChannelDetached(channelId: Long) = channelId in detachedChannels

    /**
     * Trims an over-long channel and decides whether it is now detached. Returns whether it trimmed.
     *
     * The two jobs are inseparable: trimming while paging upwards is exactly the moment the live
     * tail is discarded, and that is what detachment means.
     *
     * JADX decompiled the predicate as `z5 = (...) ? false : false`, i.e. unreadable. The rules
     * below come from the Vineflower decompile of the DEX:
     *
     * * over [MAX_MESSAGES_PER_CHANNEL] entries, keep [TRIMMED_MESSAGES_PER_CHANNEL] from the end
     *   the load is growing from — oldest when appending at the top, newest otherwise;
     * * become detached when the trim dropped the tail (`trimmed && isAppendingTop && !isPresent`)
     *   or when this was a jump;
     * * become re-attached only when already detached and the chunk reaches the present.
     */
    private fun updateDetachedState(
        channelId: Long,
        channelMessages: TreeMap<Long, Message>,
        isAppendingTop: Boolean,
        isPresent: Boolean,
        isJump: Boolean,
    ): Boolean {
        val trimmed = channelMessages.size >= MAX_MESSAGES_PER_CHANNEL
        if (trimmed) {
            val threshold =
                if (isAppendingTop) TRIMMED_MESSAGES_PER_CHANNEL
                else channelMessages.size - TRIMMED_MESSAGES_PER_CHANNEL
            val discarded = channelMessages.keys.filterIndexed { index, _ ->
                if (isAppendingTop) index >= threshold else index < threshold
            }
            discarded.forEach(channelMessages::remove)
        }

        val wasDetached = isChannelDetached(channelId)
        val detachedByTrim = trimmed && isAppendingTop && !isPresent
        if (!wasDetached && (detachedByTrim || isJump)) {
            setDetached(channelId, true)
        } else if (wasDetached && isPresent) {
            setDetached(channelId, false)
        }
        return trimmed
    }

    private fun setDetached(channelId: Long, detached: Boolean) {
        val changed = if (detached) detachedChannels.add(channelId) else detachedChannels.remove(channelId)
        if (changed) detachedSubject.onNext(Collections.unmodifiableSet(detachedChannels.toSet()))
    }

    /**
     * Republishes only the channels in [updatedChannels], reusing the previous snapshot's lists
     * for everything else so unchanged channels keep referential identity for `distinctUntilChanged`.
     */
    private fun publishIfUpdated(force: Boolean = false) {
        if (updatedChannels.isEmpty() && !force) return
        val next = HashMap<Long, List<Message>>(snapshot.size + updatedChannels.size)
        updatedChannels.forEach { channelId ->
            messages[channelId]?.let { next[channelId] = Collections.unmodifiableList(it.values.toList()) }
        }
        snapshot.forEach { (channelId, channelMessages) ->
            if (channelId !in updatedChannels) next[channelId] = channelMessages
        }
        updatedChannels.clear()
        snapshot = Collections.unmodifiableMap(next)
        messagesSubject.onNext(snapshot)
        messageCacheTryPersist()
    }

    /**
     * Persists at most once per [CACHE_PERSIST_INTERVAL], and only when the payload changed.
     *
     * Inside the window the write is deferred rather than dropped, so the last mutation before
     * the app is killed still reaches disk. The extra second on the reschedule keeps the timer
     * from firing microseconds early and immediately deferring again.
     */
    @Synchronized
    private fun messageCacheTryPersist() {
        if (!cacheEnabled) return
        val now = clock.currentTimeMillis()
        val nextPersistAt = cachePersistedAt + CACHE_PERSIST_INTERVAL
        if (nextPersistAt < now) {
            cachePersistedAt = now
            val next = computeMessagesCache()
            if (cacheSnapshot != next) {
                cacheSnapshot = next
                cache.set(next)
            }
        } else {
            cachePersistSubscription?.unsubscribe()
            cachePersistSubscription = Observable
                .timer(nextPersistAt - now + PERSIST_RESCHEDULE_SLACK_MS, TimeUnit.MILLISECONDS, persistScheduler)
                .subscribe({ messageCacheTryPersist() }, { })
        }
    }

    /** The most recently visited [CACHE_MAX_CHANNELS] channels, tail-trimmed for size. */
    private fun computeMessagesCache(): Map<Long, List<Message>> {
        val result = HashMap<Long, List<Message>>()
        var remaining = CACHE_MAX_CHANNELS
        messages.entries.reversed().forEach { (channelId, channelMessages) ->
            if (remaining <= 0 || channelMessages.isEmpty()) return@forEach
            val count = if (channelId == selectedChannelId) CACHE_SELECTED_MESSAGES else CACHE_OTHER_MESSAGES
            result[channelId] = channelMessages.values.toList().takeLast(count)
            remaining--
        }
        return result
    }

    companion object {
        const val STORE_MESSAGES_CACHE_V38 = "STORE_MESSAGES_CACHE_V38"
        const val MAX_MESSAGES_PER_CHANNEL = 200
        const val TRIMMED_MESSAGES_PER_CHANNEL = 100

        /** Minimum gap between disk writes. Reference `CACHE_PERSIST_INTERVAL`. */
        const val CACHE_PERSIST_INTERVAL = 60_000L

        private const val PERSIST_RESCHEDULE_SLACK_MS = 1_000L
        private const val CACHE_MAX_CHANNELS = 8
        private const val CACHE_SELECTED_MESSAGES = 20
        private const val CACHE_OTHER_MESSAGES = 10

        /**
         * Adds one reaction, preserving emoji order.
         *
         * The early return is the fix for double-counted own reactions: adding a reaction
         * optimistically and then receiving the gateway echo of the same reaction would otherwise
         * increment twice. If this emoji is already flagged `me`, the echo is a no-op.
         */
        internal fun addReaction(message: Message, emoji: MessageReactionEmoji, isMe: Boolean): Message {
            val reactions = message.getReactionsMap()
            val key = emoji.reactionKey
            if (isMe && reactions[key]?.me == true) return message

            val existing = reactions[key]
            val updated = LinkedHashMap(reactions)
            updated[key] = if (existing != null) {
                MessageReaction(existing.count + 1, existing.emoji, existing.me || isMe)
            } else {
                MessageReaction(1, emoji, isMe)
            }
            return withReactions(message, updated)
        }

        /**
         * Removes one reaction, preserving emoji order and collapsing to `null` when empty.
         *
         * The `me` guard mirrors [addReaction]: a removal attributed to the current user is
         * ignored when the current user is not flagged on that emoji, so an echoed removal cannot
         * decrement a count the local state never incremented.
         */
        internal fun removeReaction(message: Message, emoji: MessageReactionEmoji, isMe: Boolean): Message {
            val reactions = message.getReactionsMap()
            val key = emoji.reactionKey
            val existing = reactions[key] ?: return message
            if (isMe && !existing.me) return message

            val updated = LinkedHashMap(reactions)
            if (existing.count == 1) {
                updated.remove(key)
            } else {
                updated[key] = MessageReaction(existing.count - 1, existing.emoji, existing.me && !isMe)
            }
            return withReactions(message, updated)
        }

        /**
         * `null` rather than an empty list when no reactions remain — the two are distinguishable
         * on the wire and the row renderer keys off the null.
         */
        private fun withReactions(message: Message, reactions: Map<String, MessageReaction>): Message =
            message.copy(reactions = reactions.values.toList().takeIf { it.isNotEmpty() })
    }
}

/**
 * Messages that exist only on this device: queued, failed, captcha-blocked, or AutoMod-blocked.
 *
 * Kept apart from [StoreMessagesHolder] because they obey different rules — they have synthetic
 * ids, they are never trimmed, they survive a process restart so an offline send is not lost, and
 * they are merged into the visible list only when the channel is attached.
 */
class StoreLocalMessagesHolder @JvmOverloads constructor(
    private val cache: Persister<Map<Long, List<LocalEntry>>> = Persister(STORE_LOCAL_MESSAGES_CACHE_V11, emptyMap()),
) {
    /**
     * A local message plus its nonce.
     *
     * **Divergence:** the reference persists bare `Message`s and re-reads the nonce from
     * `Message.nonce`. Storing it alongside makes lookup by nonce O(1) and independent of the
     * message model, at the cost of an incompatible on-disk shape under the same cache key —
     * acceptable because this project has no previous release to migrate from.
     */
    data class LocalEntry(val nonce: String, val message: Message)

    private val messages = HashMap<Long, TreeMap<Long, LocalEntry>>()
    private val updatedChannels = HashSet<Long>()
    private var cacheEnabled = false
    private var cacheSnapshot: Map<Long, List<LocalEntry>> = emptyMap()
    private var snapshot: Map<Long, List<Message>> = emptyMap()
    private val subject = BehaviorSubject.create(snapshot).toSerialized()

    /**
     * Restores queued messages.
     *
     * A corrupt blob is discarded rather than propagated: failing to start because a single
     * unsent message cannot be deserialized would be far worse than losing it.
     */
    @Synchronized
    @JvmOverloads
    fun restore(enabled: Boolean = true) {
        messages.clear()
        updatedChannels.clear()
        if (enabled) {
            try {
                var repaired = false
                cache.get().forEach { (channelId, entries) ->
                    val restored = TreeMap<Long, LocalEntry>().apply {
                        entries.forEach { entry ->
                            if (entry.isRestorable(channelId)) put(entry.message.id, entry)
                            else repaired = true
                        }
                    }
                    if (restored.isNotEmpty()) {
                        messages[channelId] = restored
                        updatedChannels += channelId
                    }
                }
                if (repaired) {
                    cache.set(messages.mapValues { it.value.values.toList() }, persist = true)
                }
            } catch (failure: RuntimeException) {
                Log.e(javaClass.name, "Error restoring cached local messages", failure)
                clearCache()
            }
        }
        cacheEnabled = enabled
        publishIfUpdated(force = true)
    }

    @Synchronized
    fun addMessage(message: Message) = addMessage(message.channelId, requireNotNull(message.nonce), message)

    @Synchronized
    fun addMessage(channelId: Long, nonce: String, message: Message) {
        val normalized = message.copy(channelId = channelId, nonce = nonce)
        val channelMessages = messages.getOrPut(channelId) { TreeMap() }
        channelMessages.entries.removeAll { it.value.nonce == nonce }
        channelMessages[normalized.id] = LocalEntry(nonce, normalized)
        updatedChannels += channelId
        publishIfUpdated()
    }

    @Synchronized
    fun getMessage(channelId: Long, nonce: String): Message? =
        messages[channelId]?.values?.firstOrNull { it.nonce == nonce }?.message

    @Synchronized
    fun deleteMessage(message: Message): Message? {
        val nonce = message.nonce ?: return null
        return deleteMessage(message.channelId, nonce)
    }

    @Synchronized
    fun deleteMessage(channelId: Long, nonce: String): Message? {
        val channelMessages = messages[channelId] ?: return null
        val entry = channelMessages.entries.firstOrNull { it.value.nonce == nonce } ?: return null
        channelMessages.remove(entry.key)
        if (channelMessages.isEmpty()) messages.remove(channelId)
        updatedChannels += channelId
        publishIfUpdated()
        return entry.value.message
    }

    @Synchronized
    fun deleteMessage(channelId: Long, messageId: Long) {
        val channelMessages = messages[channelId] ?: return
        if (channelMessages.remove(messageId) == null) return
        if (channelMessages.isEmpty()) messages.remove(channelId)
        updatedChannels += channelId
        publishIfUpdated()
    }

    @Synchronized
    fun clearCache() {
        messages.clear()
        updatedChannels.clear()
        cacheSnapshot = emptyMap()
        cache.clear(persist = true)
        snapshot = emptyMap()
        subject.onNext(snapshot)
    }

    @Synchronized fun getMessages(channelId: Long): List<Message> = snapshot[channelId].orEmpty()
    @Synchronized fun getAll(): Map<Long, List<Message>> = snapshot
    @Synchronized fun getFlattenedMessages(): List<Message> = snapshot.values.flatten()
    fun observeMessages(): Observable<Map<Long, List<Message>>> = subject.distinctUntilChanged()

    private fun LocalEntry.isRestorable(channelId: Long): Boolean {
        val author = message.author ?: return false
        return channelId > 0L && nonce.isNotBlank() && message.id != 0L &&
            message.channelId == channelId && message.nonce == nonce && message.isLocal() &&
            author.id > 0L && author.username.isNotBlank() && message.timestamp != null
    }

    private fun publishIfUpdated(force: Boolean = false) {
        if (updatedChannels.isEmpty() && !force) return
        updatedChannels.clear()
        snapshot = Collections.unmodifiableMap(messages.mapValues { (_, channelMessages) ->
            Collections.unmodifiableList(channelMessages.values.map(LocalEntry::message))
        })
        subject.onNext(snapshot)
        if (cacheEnabled) {
            val next = messages.mapValues { it.value.values.toList() }
            if (cacheSnapshot != next) {
                cacheSnapshot = next
                cache.set(next)
            }
        }
    }

    companion object {
        const val STORE_LOCAL_MESSAGES_CACHE_V11 = "STORE_LOCAL_MESSAGES_CACHE_V11"
    }
}

/**
 * The message store: history, optimistic local messages, and the send/edit/delete pipeline.
 *
 * The visible list for a channel is `synced + local`, except while the channel is detached, when
 * only synced messages are shown — a queued message must not appear stranded in a historical
 * window it was not sent into.
 *
 * The send pipeline is *installed*, not constructed: [installSendPipeline] binds the REST-backed
 * [MessageSendCoordinator] once `StoreStream` has a client. Until then the store is a pure
 * read model, which is what lets the persisted caches be restored before the network exists.
 */
class StoreMessages @JvmOverloads constructor(
    private val observationDeck: ObservationDeck,
    val holder: StoreMessagesHolder = StoreMessagesHolder(),
    val localMessagesHolder: StoreLocalMessagesHolder = StoreLocalMessagesHolder(),
    private val clock: Clock = Clock { System.currentTimeMillis() },
) : StoreV2() {
    private var messagesSnapshot: Map<Long, List<Message>> = emptyMap()

    private var dispatcher: Dispatcher? = null
    private var sendCoordinator: MessageSendCoordinator? = null
    private var slowMode: StoreSlowMode? = null
    private var contentResolver: ContentResolver? = null
    private var deleteRemoteMessage: (channelId: Long, messageId: Long) -> Unit = { _, _ -> }
    private var scheduleBackgroundSend: () -> Unit = {}
    private var cancelBackgroundSend: () -> Unit = {}

    /**
     * False until the boot-time resend sweep has finished.
     *
     * The background send worker waits on this so it does not start a second attempt for messages
     * the foreground process is already retrying.
     */
    private val initResendFinished = BehaviorSubject.create(false)

    // region lifecycle

    /**
     * Restores both caches and, when the send pipeline is installed, re-arms queued messages.
     *
     * Order matters: captcha-blocked messages are failed *before* the resend sweep, because a
     * captcha challenge cannot be answered automatically and retrying it would burn an attempt.
     *
     * Both holders restore with their cache **enabled**. The parity audit read the reference's
     * `StoreLocalMessagesHolder.init$default(holder, false, 1, null)` as "disabled"; mask `1`
     * means argument 0 takes its *default*, which is `true`, so the `false` in that call is only
     * a placeholder.
     */
    @StoreThread
    fun restore() {
        holder.restore(enabled = true)
        localMessagesHolder.restore(enabled = true)
        markLocalCaptchaRequiredMessagesFailed()
        resendAllLocalMessages()
        markChanged()
    }

    /**
     * Binds the send/edit/delete pipeline.
     *
     * Call once, after the REST client exists. [MessageSendCoordinator] owns the per-channel
     * `MessageQueue` map, the queue executor and the retry/backoff policy — the reference keeps
     * those fields on this store (`messageQueues`, `queueExecutor`, `getOrCreateMessageQueue`),
     * but duplicating them here would give two owners for the same ordering guarantee.
     *
     * Installing this makes the store the single consumer of
     * [MessageSendCoordinator.observeUpdates]; any other subscriber that also mutates the holders
     * will double-apply every update.
     */
    fun installSendPipeline(
        dispatcher: Dispatcher,
        sendCoordinator: MessageSendCoordinator,
        slowMode: StoreSlowMode? = null,
        contentResolver: ContentResolver? = null,
        deleteRemoteMessage: (channelId: Long, messageId: Long) -> Unit = { _, _ -> },
        scheduleBackgroundSend: () -> Unit = {},
        cancelBackgroundSend: () -> Unit = {},
    ) {
        this.dispatcher = dispatcher
        this.sendCoordinator = sendCoordinator
        this.slowMode = slowMode
        this.contentResolver = contentResolver
        this.deleteRemoteMessage = deleteRemoteMessage
        this.scheduleBackgroundSend = scheduleBackgroundSend
        this.cancelBackgroundSend = cancelBackgroundSend

        sendCoordinator.observeUpdates().subscribe({ update ->
            dispatcher.schedule { applySendUpdate(update) }
        }, { })
    }

    fun observeInitResendFinished(): Observable<Boolean> = initResendFinished

    // endregion

    // region gateway and history handlers

    @StoreThread
    fun handleChannelSelected(channelId: Long) {
        holder.selectChannel(channelId)
        markChanged()
    }

    /**
     * On disconnect, invalidate history; on reconnect, let every queue retry.
     *
     * The reconnect half is what drains messages that failed while offline without waiting for
     * their individual backoffs to expire.
     */
    @StoreThread
    fun handleConnected(connected: Boolean) {
        if (!connected) holder.invalidate() else sendCoordinator?.handleConnected()
    }

    /** Records the current user so reaction updates can be attributed. */
    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        holder.setMyUserId(payload.me?.id ?: 0L)
    }

    @StoreThread
    fun handleApplicationCommandInteraction(
        sendData: ApplicationCommandLocalSendData,
        interaction: InteractionStateUpdate,
        failed: Boolean,
        loading: Boolean,
    ) {
        val message = localMessagesHolder.getMessage(sendData.channelId, sendData.nonceString) ?: return
        localMessagesHolder.addMessage(
            message.copy(
                type = if (failed) TYPE_APPLICATION_COMMAND_FAILED else TYPE_APPLICATION_COMMAND,
                flags = if (loading) MESSAGE_FLAG_LOADING else null,
                interaction = message.interaction?.copy(id = interaction.id),
            ),
        )
        markChanged()
    }

    @StoreThread
    fun handleApplicationCommandModal(sendData: ApplicationCommandLocalSendData) {
        if (localMessagesHolder.deleteMessage(sendData.channelId, sendData.nonceString) != null) markChanged()
    }

    @StoreThread
    fun handleMessageCreate(message: Message) = handleMessageCreate(message.channelId, message, message.nonce)

    @StoreThread
    @JvmOverloads
    fun handleMessageCreate(channelId: Long, message: Message, nonce: String? = null) {
        nonce?.let { localMessagesHolder.deleteMessage(channelId, it) }
        holder.addMessage(channelId, message, nonce)
        markChanged()
    }

    @StoreThread
    fun handleMessageCreate(messages: List<com.discord.api.message.Message>) {
        messages.forEach { apiMessage ->
            apiMessage.nonce?.let { localMessagesHolder.deleteMessage(apiMessage.channelId, it) }
        }
        holder.addMessages(messages.map(::Message))
        markChanged()
    }

    @StoreThread
    fun handleLocalMessageCreate(channelId: Long, nonce: String, message: Message) {
        localMessagesHolder.addMessage(channelId, nonce, message)
        markChanged()
    }

    @StoreThread
    fun handleLocalMessageCreate(message: Message) {
        localMessagesHolder.addMessage(message)
        markChanged()
    }

    @StoreThread
    fun handleLocalMessageDelete(channelId: Long, nonce: String) {
        if (localMessagesHolder.deleteMessage(channelId, nonce) != null) markChanged()
    }

    @StoreThread
    fun handleLocalMessageDelete(message: Message) {
        if (localMessagesHolder.deleteMessage(message) != null) markChanged()
    }

    @StoreThread
    fun handleMessageUpdate(channelId: Long, messageId: Long, merge: (Message) -> Message) {
        holder.updateMessage(channelId, messageId, merge)
        markChanged()
    }

    @StoreThread
    fun handleMessageUpdate(message: com.discord.api.message.Message) {
        holder.updateMessages(message)
        markChanged()
    }

    @StoreThread
    fun handlePollVote(event: PollVoteEvent, added: Boolean, meUserId: Long) {
        handleMessageUpdate(event.channelId, event.messageId) { it.withPollVote(event, added, meUserId) }
    }

    @StoreThread
    fun handleReactionUpdate(updates: List<MessageReactionUpdate>, add: Boolean) {
        holder.updateReactions(updates, add)
        markChanged()
    }

    /**
     * Single-update overload kept for the gateway router.
     *
     * [meUserId] is redundant once [handleConnectionOpen] has run — the holder knows the current
     * user — but the router still supplies it, so it is used to keep the holder's identity fresh
     * for clients that never saw a `READY` (for example a resumed session).
     */
    @StoreThread
    fun handleReaction(update: MessageReactionUpdate, added: Boolean, meUserId: Long) {
        holder.setMyUserId(meUserId)
        handleReactionUpdate(listOf(update), added)
    }

    @StoreThread
    fun handleReactionRemoveAll(channelId: Long, messageId: Long) {
        holder.removeAllReactions(channelId, messageId)
        markChanged()
    }

    @StoreThread
    fun handleReactionRemoveEmoji(channelId: Long, messageId: Long, emoji: MessageReactionEmoji) {
        holder.removeEmojiReactions(channelId, messageId, emoji)
        markChanged()
    }

    @StoreThread
    fun handleMessageDelete(channelId: Long, messageId: Long) = handleMessageDeleteBulk(channelId, listOf(messageId))

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
        sendCoordinator?.cancelAll()
        holder.clear()
        localMessagesHolder.clearCache()
        markChanged()
    }

    // endregion

    // region send pipeline

    /**
     * Queues a message and returns its eventual result.
     *
     * The optimistic message is visible before this returns; everything after that is driven by
     * [applySendUpdate]. A background send is scheduled at the same time so a message queued and
     * then backgrounded still leaves the device.
     *
     * [stickers] now ride on [MessageSendCoordinator.Send] and therefore reach `sticker_ids` on the
     * wire as well as the local row.
     *
     * Still local-only, because [MessageSendCoordinator.Send] does not carry them: [application],
     * [messageActivity] and explicit [mentions] are attached to the optimistic message for
     * rendering but are absent from the queued request body.
     */
    @Suppress("LongParameterList")
    @JvmOverloads
    fun sendMessage(
        channelId: Long,
        author: User,
        content: String,
        mentions: List<User>? = null,
        attachments: List<UploadAttachment>? = null,
        stickers: List<Sticker>? = null,
        messageReference: MessageReference? = null,
        allowedMentions: MessageAllowedMentions? = null,
        application: Application? = null,
        activity: Activity? = null,
        messageActivity: MessageActivity? = null,
        lastManualAttemptTimestamp: Long? = null,
        initialAttemptTimestamp: Long? = null,
        numRetries: Int? = null,
        captchaSolution: CaptchaSolution? = null,
        guildId: Long? = null,
        location: String = MessageSendLocation.OTHER,
        flags: Long? = null,
    ): Observable<MessageResult> {
        val coordinator = sendCoordinator ?: return Observable.just(MessageResult.NoValidContent)
        val submission = coordinator.send(
            MessageSendCoordinator.Send(
                channelId = channelId,
                guildId = guildId,
                content = content,
                author = author.synthesizeApiUser(),
                allowedMentions = allowedMentions?.toRestApiParams(),
                captcha = captchaSolution,
                attachments = attachments,
                stickers = stickers,
                location = location,
                flags = flags,
            ),
        )
        return when (submission) {
            is MessageSendCoordinator.Submission.Rejected -> Observable.just(submission.result)
            is MessageSendCoordinator.Submission.Accepted -> {
                val enriched = submission.optimistic.copy(
                    mentions = mentions?.map { it.synthesizeApiUser() },
                    stickers = stickers,
                    messageReference = messageReference ?: submission.optimistic.messageReference,
                    application = application,
                    activity = messageActivity,
                    lastManualAttemptTimestamp = lastManualAttemptTimestamp ?: clock.currentTimeMillis(),
                    initialAttemptTimestamp = initialAttemptTimestamp ?: clock.currentTimeMillis(),
                    numRetries = numRetries ?: 0,
                )
                dispatcher?.schedule { handleLocalMessageCreate(enriched) }
                scheduleBackgroundSend()
                awaitResult(coordinator, submission.requestId)
            }
        }
    }

    @JvmOverloads
    fun sendMessage(
        message: Message,
        applicationCommandLocalSendData: ApplicationCommandLocalSendData,
        attachments: List<UploadAttachment>? = null,
    ): Observable<MessageResult> {
        val coordinator = sendCoordinator ?: return Observable.just(MessageResult.NoValidContent)
        dispatcher?.schedule { handleLocalMessageCreate(message) }
        scheduleBackgroundSend()
        return coordinator.sendApplicationCommand(
            message.synthesizeApiMessage(),
            applicationCommandLocalSendData,
            attachments,
        )
    }

    /**
     * Re-queues a failed or queued local message.
     *
     * The type guard is not defensive coding: an *automatic* retry may only touch a message that
     * is still queued (`-1`) or captcha-blocked (`-6`), while a *manual* retry may only touch one
     * that has already failed (`-2`). Mixing them would let the boot sweep silently re-send a
     * message the user chose not to retry.
     */
    @JvmOverloads
    fun resendMessage(
        message: Message,
        isAutoAttempt: Boolean = false,
        captchaSolution: CaptchaSolution? = null,
    ): Observable<MessageResult> {
        val type = message.type
        val allowed =
            if (isAutoAttempt) type == TYPE_SENDING || type == TYPE_CAPTCHA_REQUIRED else type == TYPE_FAILED
        require(allowed) { "Incorrect $isAutoAttempt auto attempt and message type ${message.type}" }

        val author = message.author ?: return Observable.just(MessageResult.NoValidContent)
        dispatcher?.schedule { handleLocalMessageDelete(message) }

        return sendMessage(
            channelId = message.channelId,
            author = com.discord.models.user.CoreUser(author),
            content = message.content.orEmpty(),
            mentions = message.mentions?.map { com.discord.models.user.CoreUser(it) },
            // A resend re-reads the original content URIs. One that has since been revoked or
            // deleted is dropped rather than failing the whole resend.
            attachments = message.localAttachments?.mapNotNull { attachment ->
                contentResolver?.let { resolver ->
                    runCatching { attachment.toUploadAttachment(resolver) }.getOrNull()
                }
            },
            stickers = message.stickers,
            messageReference = message.messageReference,
            allowedMentions = message.allowedMentions,
            // A manual retry starts a fresh manual-attempt clock; an automatic one keeps the
            // original so the hour-long queue timeout is measured from the user's action.
            lastManualAttemptTimestamp = if (isAutoAttempt) message.lastManualAttemptTimestamp else null,
            initialAttemptTimestamp = message.initialAttemptTimestamp,
            numRetries = (message.numRetries ?: 0) + 1,
            captchaSolution = captchaSolution,
            guildId = message.guildId,
            location = if (isAutoAttempt) MessageSendLocation.OTHER else MessageSendLocation.RETRY,
            flags = message.flags,
        )
    }

    /** Re-sends after the user solved a captcha challenge for a blocked message. */
    fun resendMessageWithCaptcha(channelId: Long, nonce: String?, captchaSolution: CaptchaSolution) {
        val message = nonce?.let { localMessagesHolder.getMessage(channelId, it) } ?: return
        resendMessage(message, isAutoAttempt = true, captchaSolution = captchaSolution).subscribe({ }, { })
    }

    /**
     * Re-queues everything still marked as sending, then flips [initResendFinished].
     *
     * Failures are collected rather than short-circuited: one message that cannot be re-sent must
     * not prevent the rest of the queue from draining.
     */
    @StoreThread
    fun resendAllLocalMessages() {
        val pending = localMessagesHolder.getFlattenedMessages().filter { it.type == TYPE_SENDING }
        if (pending.isEmpty() || sendCoordinator == null) {
            initResendFinished.onNext(true)
            return
        }
        Observable.mergeDelayError(Observable.from(pending.map { resendMessage(it, isAutoAttempt = true) }))
            .subscribe({ }, { initResendFinished.onNext(true) }, { initResendFinished.onNext(true) })
    }

    /**
     * Fails every captcha-blocked message left over from a previous run.
     *
     * A captcha challenge is bound to the request that triggered it and cannot be replayed, so
     * these can only be surfaced to the user as failed.
     */
    @StoreThread
    fun markLocalCaptchaRequiredMessagesFailed() {
        localMessagesHolder.getFlattenedMessages()
            .filter { it.type == TYPE_CAPTCHA_REQUIRED }
            .forEach { markMessageFailed(it.channelId, it.nonce) }
    }

    /** Moves a queued message into the failed state, exposing the retry affordance. */
    fun markMessageFailed(channelId: Long, nonce: String?) {
        val message = nonce?.let { localMessagesHolder.getMessage(channelId, it) } ?: return
        dispatcher?.schedule { handleSendMessageFailure(message) }
    }

    fun editMessage(
        messageId: Long,
        channelId: Long,
        content: String,
        allowedMentions: MessageAllowedMentions? = null,
    ): Observable<MessageResult> {
        val coordinator = sendCoordinator ?: return Observable.just(MessageResult.NoValidContent)
        val message = getMessage(channelId, messageId) ?: return Observable.just(MessageResult.NoValidContent)
        val submission = coordinator.edit(message, content, allowedMentions?.toRestApiParams())
        return when (submission) {
            is MessageSendCoordinator.Submission.Rejected -> Observable.just(submission.result)
            is MessageSendCoordinator.Submission.Accepted -> awaitResult(coordinator, submission.requestId)
        }
    }

    /**
     * Deletes a message.
     *
     * A remote message goes to the API and is removed when the gateway echoes the deletion; a
     * local or ephemeral one never existed server-side, so it is dropped directly — cancelling
     * its queued request first, or the send would race the delete.
     */
    fun deleteMessage(message: Message?) {
        if (message == null) return
        if (!message.isLocal() && !message.isEphemeralMessage()) {
            deleteRemoteMessage(message.channelId, message.id)
            return
        }
        dispatcher?.schedule {
            if (message.isLocal()) {
                message.nonce?.let { sendCoordinator?.cancel(it) }
                handleLocalMessageDelete(message)
            } else if (message.isEphemeralMessage()) {
                handleMessageDelete(ModelMessageDelete(message.channelId, message.id))
            }
        }
    }

    @StoreThread
    fun deleteLocalMessage(channelId: Long, nonce: String) {
        cancelMessageSend(channelId, nonce)
        handleLocalMessageDelete(channelId, nonce)
    }

    /**
     * Cancels a queued or in-flight send.
     *
     * [channelId] is accepted for signature parity with the reference; the coordinator resolves
     * the owning queue from the request id itself.
     */
    @Suppress("UNUSED_PARAMETER")
    fun cancelMessageSend(channelId: Long, requestId: String) {
        sendCoordinator?.cancel(requestId)
    }

    /**
     * Projects one coordinator update onto store state.
     *
     * This is the only place local-message state transitions live, so the mapping from
     * [MessageResult] to a local message `type` is stated once.
     */
    @StoreThread
    private fun applySendUpdate(update: MessageSendCoordinator.Update) {
        when (update) {
            is MessageSendCoordinator.Update.Optimistic ->
                handleLocalMessageCreate(update.message.channelId, update.requestId, update.message)

            is MessageSendCoordinator.Update.Reconciled -> {
                slowMode?.onMessageSent(update.message.channelId)
                handleLocalMessageDelete(update.message.channelId, update.requestId)
                handleMessageCreate(update.message)
                cancelBackgroundSend()
            }

            is MessageSendCoordinator.Update.Cancelled ->
                localMessagesHolder.getAll().forEach { (channelId, channelMessages) ->
                    if (channelMessages.any { it.nonce == update.requestId }) {
                        handleLocalMessageDelete(channelId, update.requestId)
                    }
                }

            is MessageSendCoordinator.Update.Failed -> when (val result = update.result) {
                is MessageResult.Slowmode -> {
                    handleSendMessageFailure(update.message)
                    slowMode?.onCooldown(update.message.channelId, result.cooldownMs, StoreSlowMode.Type.MessageSend)
                }

                is MessageResult.CaptchaRequired -> handleSendMessageCaptchaRequired(update.message)
                is MessageResult.AutoModBlock -> handleSendMessageFailureAutoMod(update.message)
                is MessageResult.ValidationError -> handleSendMessageValidationError(update.message, result.message)
                // A rate limit or a network failure is retried inside the queue and is not a
                // user-visible failure; leave the message queued.
                is MessageResult.RateLimited, MessageResult.NetworkFailure -> Unit
                else -> handleSendMessageFailure(update.message)
            }
        }
    }

    /**
     * Recreates the local message as failed.
     *
     * The old entry is deleted and a new one inserted rather than updated in place, because the
     * failed message gets a fresh synthetic id and the list has to re-sort around it.
     */
    @StoreThread
    private fun handleSendMessageFailure(localMessage: Message) {
        localMessagesHolder.deleteMessage(localMessage)
        handleLocalMessageCreate(localMessage.copy(type = TYPE_FAILED))
    }

    @StoreThread
    private fun handleSendMessageCaptchaRequired(localMessage: Message) {
        handleLocalMessageCreate(localMessage.copy(type = TYPE_CAPTCHA_REQUIRED))
    }

    @StoreThread
    private fun handleSendMessageFailureAutoMod(
        localMessage: Message,
        editedTimestamp: UtcDateTime? = UtcDateTime(clock.currentTimeMillis()),
    ) {
        handleLocalMessageCreate(localMessage.copy(type = TYPE_AUTOMOD_BLOCKED, editedTimestamp = editedTimestamp))
    }

    /**
     * Drops a message the server refused to accept at all.
     *
     * Unlike a failure this offers no retry: the content itself is invalid, so leaving it in the
     * list would present a button that can only fail again.
     */
    @StoreThread
    private fun handleSendMessageValidationError(localMessage: Message?, errorMessage: String) {
        Log.e(javaClass.name, "ValidationError: $errorMessage")
        deleteMessage(localMessage)
    }

    /** Bridges a queued request's terminal outcome back to the caller's observable. */
    private fun awaitResult(
        coordinator: MessageSendCoordinator,
        requestId: String,
    ): Observable<MessageResult> = coordinator.observeUpdates()
        .filter { it.requestId == requestId }
        .map { update ->
            when (update) {
                is MessageSendCoordinator.Update.Reconciled ->
                    MessageResult.Success(update.message.synthesizeApiMessage())
                is MessageSendCoordinator.Update.Failed -> update.result
                is MessageSendCoordinator.Update.Cancelled -> MessageResult.UserCancelled
                is MessageSendCoordinator.Update.Optimistic -> null
            }
        }
        .filter { it != null }
        .map { it!! }
        .take(1)

    // endregion

    // region reads

    fun getMessages(channelId: Long): List<Message> = messagesSnapshot[channelId].orEmpty()

    /**
     * The synced message only.
     *
     * Deliberately excludes local messages: `StoreMessageReplies` resolves reply targets through
     * this, and a reply can never point at a message that has not been sent.
     */
    @StoreThread
    fun getMessage(channelId: Long, messageId: Long): Message? = holder.getMessagesForChannel(channelId)?.get(messageId)

    fun observeMessagesForChannel(channelId: Long): Observable<List<Message>> =
        observationDeck.connectRx(this) { getMessages(channelId) }.distinctUntilChanged()

    fun observeMessagesForChannel(channelId: Long, messageId: Long): Observable<Message?> =
        observeMessagesForChannel(channelId)
            .map { messages -> messages.firstOrNull { it.id == messageId } }
            .distinctUntilChanged()

    fun observeMessage(channelId: Long, messageId: Long): Observable<Message?> =
        observeMessagesForChannel(channelId, messageId)

    /** Every currently detached channel. [StoreMessagesLoader] pages from this. */
    fun getAllDetached(): Observable<Set<Long>> = holder.observeDetachedChannels()

    fun observeIsDetached(channelId: Long): Observable<Boolean> =
        getAllDetached().map { channelId in it }.distinctUntilChanged()

    /**
     * Merges synced and local messages.
     *
     * Local messages are **appended**, not merged by id: they carry synthetic ids derived from a
     * nonce, so sorting the combined list would interleave them into the history at arbitrary
     * points instead of keeping them pinned to the bottom where they were typed.
     */
    override fun snapshotData() {
        val detached = holder.getDetached()
        val channels = holder.getAll().keys + localMessagesHolder.getAll().keys
        messagesSnapshot = Collections.unmodifiableMap(channels.associateWith { channelId ->
            val synced = holder.getMessages(channelId)
            if (channelId in detached) {
                synced
            } else {
                Collections.unmodifiableList(synced + localMessagesHolder.getMessages(channelId))
            }
        })
    }

    // endregion

    companion object {
        /** Queued, not yet acknowledged by the server. */
        const val TYPE_SENDING = -1

        /** Send failed; the user can retry. */
        const val TYPE_FAILED = -2

        /** Blocked pending a captcha solve. */
        const val TYPE_CAPTCHA_REQUIRED = -6

        /** Blocked by AutoMod. */
        const val TYPE_AUTOMOD_BLOCKED = -8

        private const val TYPE_APPLICATION_COMMAND = -5
        private const val TYPE_APPLICATION_COMMAND_FAILED = -4
        private const val MESSAGE_FLAG_LOADING = 128L
    }
}

internal fun Message.withPollVote(event: PollVoteEvent, added: Boolean, meUserId: Long): Message {
    val poll = poll ?: return this
    val existing = poll.results?.answerCounts.orEmpty()
    val counts = poll.answers.map { answer ->
        val count = existing.firstOrNull { it.id == answer.answerId }
            ?: PollAnswerCount(answer.answerId, 0, false)
        if (answer.answerId != event.answerId) count else count.copy(
            count = (count.count + if (added) 1 else -1).coerceAtLeast(0),
            meVoted = if (event.userId == meUserId) added else count.meVoted,
        )
    }
    return copy(poll = poll.copy(results = PollResults(poll.results?.finalized == true, counts)))
}

/**
 * Bridges the gateway/domain allowed-mentions model to the REST request body.
 *
 * `MessageSendCoordinator` accepts the REST shape while stores and messages carry the API shape;
 * the reference converts inside `MessageQueue.doSend`, which is not reachable from here.
 */
private fun MessageAllowedMentions.toRestApiParams() = RestAPIParams.Message.AllowedMentions.create(this)
