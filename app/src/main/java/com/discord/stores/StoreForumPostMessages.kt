package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionUpdate
import com.discord.api.message.PollVoteEvent
import com.discord.models.domain.ModelMessageDelete
import com.discord.models.domain.ModelPayload
import com.discord.models.message.Message
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable
import com.discord.api.message.Message as ApiMessage

/**
 * The opening message of each forum post, which the forum browser renders as the post body.
 *
 * A forum post is a thread whose id equals its first message's id, which is how [isFirstMessage]
 * identifies one without a lookup. Those messages are held here rather than in [StoreMessages]
 * because the browser needs hundreds of posts' first messages at once while loading none of their
 * histories.
 *
 * Fetching is batched and de-duplicated on purpose: [enqueueForumPostFirstMessageFetch] collects
 * ids while the browser scrolls, [flushForumPostFirstMessageQueue] drains them
 * [MAX_REQUEST_BODY_POST_IDS] at a time, and a post is skipped when it is already held, already
 * requested, or expected to arrive via `THREAD_CREATE` — one request per post, never more.
 */
class StoreForumPostMessages(
    private val dispatcher: Dispatcher,
    private val storeUser: StoreUser,
    private val observationDeck: ObservationDeck,
    private val restApi: () -> RestAPIInterface?,
) : StoreV2() {

    private val forumPostFirstMessage = HashMap<Long, Message>()
    private var forumPostFirstMessageSnapshot: Map<Long, Message> = emptyMap()

    /** Posts whose first message the client just created and will receive over the gateway. */
    private val shouldGetForumPostFirstMessageFromGateway = LinkedHashSet<Long>()
    private val forumPostFirstMessageFetchSent = HashSet<Long>()
    private val forumPostFetchQueue = HashMap<Long, MutableSet<Long>>()
    private var forumPostRequestQueueProcessing = false

    // -- Reads ----------------------------------------------------------------------------------

    fun getForumPostFirstMessage(postId: Long): Message? = forumPostFirstMessageSnapshot[postId]

    fun observeForumPostFirstMessages(): Observable<Map<Long, Message>> =
        observationDeck.connectRx(this) { forumPostFirstMessageSnapshot }.distinctUntilChanged()

    @JvmOverloads
    fun observeForumPostFirstMessage(
        parentChannelId: Long,
        postId: Long,
        fetchIfMissing: Boolean = false,
    ): Observable<Message?> {
        if (fetchIfMissing && forumPostFirstMessageSnapshot[postId] == null) {
            enqueueForumPostFirstMessageFetch(parentChannelId, postId)
            flushForumPostFirstMessageQueue(parentChannelId)
        }
        return observationDeck.connectRx(this) { forumPostFirstMessageSnapshot[postId] }
            .distinctUntilChanged()
    }

    // -- Fetch queue ----------------------------------------------------------------------------

    fun enqueueForumPostFirstMessageFetch(parentChannelId: Long, postId: Long) = dispatcher.schedule {
        if (hasForumPostFirstMessage(postId)) return@schedule
        if (isFetchingForumPostFirstMessage(postId)) return@schedule
        if (expectForumPostFirstMessageFromGateway(postId)) return@schedule
        forumPostFetchQueue.getOrPut(parentChannelId) { LinkedHashSet() }.add(postId)
    }

    /** No-op while a drain is already running; that drain picks up whatever was queued behind it. */
    fun flushForumPostFirstMessageQueue(parentChannelId: Long) = dispatcher.schedule {
        if (forumPostRequestQueueProcessing) return@schedule
        forumPostRequestQueueProcessing = true
        flushForumPostFirstMessageQueueHelper(parentChannelId)
    }

    // -- Writes ---------------------------------------------------------------------------------

    @StoreThread
    fun bulkCreateFirstMessage(messages: List<ApiMessage>) {
        val firstMessages = messages.filter(::isFirstMessage)
        if (firstMessages.isEmpty()) return
        firstMessages.forEach { message ->
            forumPostFirstMessage[message.channelId] = Message(message)
            shouldGetForumPostFirstMessageFromGateway.remove(message.channelId)
        }
        markChanged()
    }

    @StoreThread
    fun createFirstMessage(message: ApiMessage) {
        if (!isFirstMessage(message)) return
        forumPostFirstMessage[message.channelId] = Message(message)
        shouldGetForumPostFirstMessageFromGateway.remove(message.channelId)
        markChanged()
    }

    @StoreThread
    fun updateFirstMessage(message: ApiMessage) {
        val existing = forumPostFirstMessage[message.channelId] ?: return
        if (!isFirstMessage(message)) return
        forumPostFirstMessage[message.channelId] = existing.merge(message)
        shouldGetForumPostFirstMessageFromGateway.remove(message.channelId)
        markChanged()
    }

    @StoreThread
    fun deleteFirstMessage(channelId: Long) {
        val removed = forumPostFirstMessage.remove(channelId)
        shouldGetForumPostFirstMessageFromGateway.remove(channelId)
        if (removed != null) markChanged()
    }

    // -- Gateway handlers -----------------------------------------------------------------------

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        forumPostFirstMessage.clear()
        forumPostFetchQueue.clear()
        shouldGetForumPostFirstMessageFromGateway.clear()
        forumPostFirstMessageFetchSent.clear()
        forumPostRequestQueueProcessing = false
    }

    @StoreThread
    fun handleMessageCreate(message: ApiMessage) = createFirstMessage(message)

    @StoreThread
    fun handleMessageUpdate(message: ApiMessage) = updateFirstMessage(message)

    @StoreThread
    fun handleMessageDelete(messageDelete: ModelMessageDelete) {
        val message = forumPostFirstMessage[messageDelete.channelId] ?: return
        if (messageDelete.messageIds.orEmpty().contains(message.id)) deleteFirstMessage(message.channelId)
    }

    /**
     * A new post arrived. Its first message is not in the payload, but the matching `MESSAGE_CREATE`
     * is on its way, so mark it expected and suppress the REST fetch that would otherwise race it.
     */
    @StoreThread
    fun handleThreadCreateOrUpdate(channel: Channel) {
        if (hasForumPostFirstMessage(channel.id)) return
        if (isFetchingForumPostFirstMessage(channel.id)) return
        shouldGetForumPostFirstMessageFromGateway.add(channel.id)
    }

    @StoreThread
    fun handleThreadDelete(channel: Channel) {
        shouldGetForumPostFirstMessageFromGateway.remove(channel.id)
    }

    /**
     * Reference behavior reproduced deliberately: the reaction count is incremented for every
     * `MESSAGE_REACTION_ADD`, including the echo of the current user's own reaction, so an optimistic
     * local increment elsewhere would double-count. The `me` flag is still set from the payload.
     */
    @StoreThread
    fun handleReactionAdd(update: MessageReactionUpdate) {
        val message = getFirstMessageForReactionUpdate(update) ?: return
        val isMe = update.userId == storeUser.getMe().id
        val existing = message.reactions
            ?.firstOrNull { it.emoji.reactionKey == update.emoji.reactionKey }
        val reactions = if (existing == null) {
            message.reactions.orEmpty() + MessageReaction(1, update.emoji, isMe)
        } else {
            message.reactions.orEmpty().map { reaction ->
                if (reaction.emoji.reactionKey != update.emoji.reactionKey) reaction
                else MessageReaction(reaction.count + 1, reaction.emoji, isMe || reaction.me)
            }
        }
        forumPostFirstMessage[message.channelId] = message.copy(reactions = reactions)
        markChanged()
    }

    @StoreThread
    fun handleReactionRemove(update: MessageReactionUpdate) {
        val message = getFirstMessageForReactionUpdate(update) ?: return
        val isMe = update.userId == storeUser.getMe().id
        val reactions = message.reactions?.mapNotNull { reaction ->
            if (reaction.emoji.reactionKey != update.emoji.reactionKey) return@mapNotNull reaction
            val count = reaction.count - 1
            if (count > 0) MessageReaction(count, reaction.emoji, !isMe && reaction.me) else null
        }
        forumPostFirstMessage[message.channelId] = message.copy(reactions = reactions)
        markChanged()
    }

    @StoreThread
    fun handleReactionRemoveAll(update: MessageReactionUpdate) {
        val message = getFirstMessageForReactionUpdate(update) ?: return
        forumPostFirstMessage[message.channelId] = message.copy(reactions = null)
        markChanged()
    }

    @StoreThread
    fun handleReactionRemoveEmoji(update: MessageReactionUpdate) {
        val message = getFirstMessageForReactionUpdate(update) ?: return
        val reactions = message.reactions
            ?.filterNot { it.emoji.reactionKey == update.emoji.reactionKey }
        forumPostFirstMessage[message.channelId] = message.copy(reactions = reactions)
        markChanged()
    }

    @StoreThread
    fun handlePollVote(event: PollVoteEvent, added: Boolean, meUserId: Long) {
        val message = forumPostFirstMessage[event.channelId]?.takeIf { it.id == event.messageId } ?: return
        forumPostFirstMessage[event.channelId] = message.withPollVote(event, added, meUserId)
        markChanged()
    }

    /**
     * Drop every cached post body on logout.
     *
     * The reference has no `reset()` and relies on `READY`; this project wipes user state at logout,
     * so the hook exists to match the surrounding store graph.
     */
    internal fun reset() {
        forumPostFirstMessage.clear()
        forumPostFetchQueue.clear()
        shouldGetForumPostFirstMessageFromGateway.clear()
        forumPostFirstMessageFetchSent.clear()
        forumPostRequestQueueProcessing = false
        markChanged()
    }

    override fun snapshotData() {
        super.snapshotData()
        forumPostFirstMessageSnapshot = HashMap(forumPostFirstMessage)
    }

    // -- Helpers --------------------------------------------------------------------------------

    /** A forum post's opening message shares the post's id, because the thread is created from it. */
    private fun isFirstMessage(message: ApiMessage): Boolean = message.id == message.channelId

    @StoreThread
    private fun hasForumPostFirstMessage(postId: Long): Boolean =
        forumPostFirstMessage.containsKey(postId)

    @StoreThread
    private fun isFetchingForumPostFirstMessage(postId: Long): Boolean =
        forumPostFirstMessageFetchSent.contains(postId)

    @StoreThread
    private fun expectForumPostFirstMessageFromGateway(postId: Long): Boolean =
        shouldGetForumPostFirstMessageFromGateway.contains(postId)

    @StoreThread
    private fun getFirstMessageForReactionUpdate(update: MessageReactionUpdate): Message? =
        forumPostFirstMessage[update.channelId]?.takeIf { it.id == update.messageId }

    @StoreThread
    private fun flushForumPostFirstMessageQueueHelper(parentChannelId: Long) {
        val queued = forumPostFetchQueue[parentChannelId]
        if (queued.isNullOrEmpty()) {
            forumPostRequestQueueProcessing = false
            return
        }
        val batch = queued.take(MAX_REQUEST_BODY_POST_IDS)
        forumPostFetchQueue[parentChannelId] = (queued - batch.toSet()).toMutableSet()
        fetchForumPosts(parentChannelId, batch) {
            if (forumPostFetchQueue[parentChannelId].isNullOrEmpty()) {
                forumPostRequestQueueProcessing = false
            } else {
                flushForumPostFirstMessageQueueHelper(parentChannelId)
            }
        }
    }

    @StoreThread
    private fun fetchForumPosts(parentChannelId: Long, postIds: List<Long>, onTerminated: () -> Unit) {
        forumPostFirstMessageFetchSent.addAll(postIds)
        val api = restApi()
        if (api == null) {
            dispatcher.schedule(onTerminated)
            return
        }
        api.getForumPostData(parentChannelId, RestAPIParams.GetForumPostData(postIds))
            .restSubscribeOn()
            .appSubscribe(
                errorClass = StoreForumPostMessages::class.java,
                onTerminated = { dispatcher.schedule(onTerminated) },
            ) { response ->
                dispatcher.schedule {
                    bulkCreateFirstMessage(response.threads.values.mapNotNull { it.firstMessage })
                }
            }
    }

    companion object {
        /** The server caps `POST /channels/{id}/post-data` at ten post ids per request. */
        const val MAX_REQUEST_BODY_POST_IDS = 10
    }
}
