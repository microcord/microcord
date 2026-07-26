package com.discord.stores

import com.discord.api.guildmember.GuildMembersChunk
import com.discord.models.message.Message

/**
 * Lazily fetches guild members the client has never downloaded.
 *
 * A large guild is never sent in full: `READY` and `GUILD_CREATE` carry only a slice of the member
 * list. Every other member — message authors, mention targets, reaction users — arrives as a bare
 * user id, so the client asks the gateway for them on demand with `REQUEST_GUILD_MEMBERS`. Without
 * this store, chat renders those authors with no nickname, no role colour and no member avatar.
 *
 * Requests are batched rather than sent per message: [handleLoadMessages] queues every unknown id
 * in a page of history and flushes once. Ids already asked for are not asked for again until the
 * gateway either answers with the member or lists it in `not_found`.
 */
class StoreGuildMemberRequester(
    private val dispatcher: Dispatcher,
    private val guildsStore: StoreGuilds,
    private val channelsStore: StoreChannels,
    /** Sends `REQUEST_GUILD_MEMBERS`. Wire to `StoreGatewayConnection::requestGuildMembers`. */
    private val requestGuildMembers: (guildId: Long, userIds: List<Long>) -> Unit,
) : Store() {

    private val requestManager = GuildMemberRequestManager(
        guildMemberExists = ::guildMemberExists,
        onFlush = { guildId, userIds -> requestGuildMembers(guildId, userIds) },
    )

    /**
     * Messages that arrived while the socket was down, keyed by channel then message id.
     *
     * Sorted so that replaying them after reconnect keeps channel order stable, and keyed by
     * message id so the same message loaded twice is only queued once.
     */
    private val channelPendingMessages = sortedMapOf<Long, MutableMap<Long, Message>>()

    private var isConnected: Boolean = false

    @StoreThread
    fun handleConnectionOpen() {
        isConnected = true
        // A fresh session invalidates every outstanding request: the ids were asked for on a
        // socket that no longer exists and will never be answered.
        requestManager.reset()
        val pending = channelPendingMessages.toMap()
        channelPendingMessages.clear()
        pending.forEach { (channelId, messages) -> handleLoadMessages(channelId, messages.values) }
    }

    @StoreThread
    fun handleConnectionReady(isConnected: Boolean) {
        this.isConnected = isConnected
        if (isConnected) requestManager.requestUnacknowledged()
    }

    /** Marks every member in the chunk answered, including the ids the server could not find. */
    @StoreThread
    fun handleGuildMembersChunk(chunk: GuildMembersChunk) {
        chunk.members.orEmpty().forEach { requestManager.acknowledge(chunk.guildId, it.user.id) }
        chunk.notFound.orEmpty().forEach { requestManager.acknowledge(chunk.guildId, it) }
    }

    /**
     * Queues the authors and mention targets of a freshly loaded page of history.
     *
     * While disconnected the page is parked instead, because the request would be dropped.
     * Private channels have no members to request, hence the `guildId > 0` guard.
     */
    @StoreThread
    fun handleLoadMessages(channelId: Long, messages: Collection<Message>) {
        if (!isConnected) {
            val pending = channelPendingMessages.getOrPut(channelId) { HashMap(messages.size) }
            messages.forEach { pending[it.id] = it }
            return
        }
        val guildId = channelsStore.findChannelById(channelId)?.guildId ?: return
        if (guildId <= 0) return
        requestForMessages(guildId, messages)
        requestManager.flush()
    }

    /** Queues one member; the request is not sent until [performQueuedRequests]. */
    fun queueRequest(guildId: Long, userId: Long) = dispatcher.schedule {
        requestManager.queueRequest(guildId, userId)
    }

    fun performQueuedRequests() = dispatcher.schedule { requestManager.flush() }

    @StoreThread
    private fun requestForMessages(guildId: Long, messages: Iterable<Message>) {
        messages.forEach { message ->
            message.author?.let { requestManager.queueRequest(guildId, it.id) }
            message.mentions?.forEach { requestManager.queueRequest(guildId, it.id) }
        }
    }

    @StoreThread
    private fun guildMemberExists(guildId: Long, userId: Long): Boolean =
        guildsStore.getGuildMembersComputedInternal()[guildId]?.get(userId) != null

    /**
     * Per-guild request de-duplication.
     *
     * Each guild tracks three disjoint-ish sets:
     * * `pendingRequests` — queued this dispatch, not yet on the wire;
     * * `sentRequests` — asked for at least once, never asked again;
     * * `unacknowledgedRequests` — sent but never answered, replayed after a reconnect.
     *
     * Reference `com.discord.utilities.lazy.requester.GuildMemberRequestManager`. It is nested
     * here because `utilities/lazy/requester` is owned by another wave; move it there when that
     * package lands.
     */
    internal class GuildMemberRequestManager(
        private val guildMemberExists: (Long, Long) -> Boolean,
        private val onFlush: (Long, List<Long>) -> Unit,
    ) {
        private val guildRequestStates = HashMap<Long, GuildMemberRequestState>()

        fun queueRequest(guildId: Long, userId: Long) = requestState(guildId).request(userId)

        fun acknowledge(guildId: Long, userId: Long) = requestState(guildId).acknowledge(userId)

        fun flush() = guildRequestStates.values.forEach(GuildMemberRequestState::flushRequests)

        /** Re-queues everything a dead socket never answered, then sends it in one pass. */
        fun requestUnacknowledged() {
            val requeued = guildRequestStates.values.count(GuildMemberRequestState::requestUnacknowledged)
            if (requeued > 0) flush()
        }

        fun reset() = guildRequestStates.clear()

        private fun requestState(guildId: Long): GuildMemberRequestState =
            guildRequestStates.getOrPut(guildId) {
                GuildMemberRequestState(guildId) { userId -> guildMemberExists(guildId, userId) }
            }

        private inner class GuildMemberRequestState(
            private val guildId: Long,
            private val guildMemberExists: (Long) -> Boolean,
        ) {
            val pendingRequests = HashSet<Long>()
            val sentRequests = HashSet<Long>()
            val unacknowledgedRequests = HashSet<Long>()

            fun request(userId: Long): Boolean {
                if (userId in sentRequests || userId in pendingRequests || guildMemberExists(userId)) {
                    return false
                }
                pendingRequests.add(userId)
                return true
            }

            fun acknowledge(userId: Long) {
                unacknowledgedRequests.remove(userId)
                pendingRequests.remove(userId)
            }

            /**
             * Sends everything queued since the last flush, skipping ids that turned up in the
             * meantime through some other event.
             */
            fun flushRequests() {
                val toSend = HashSet<Long>()
                pendingRequests.forEach { userId ->
                    if (!guildMemberExists(userId)) {
                        unacknowledgedRequests.add(userId)
                        sentRequests.add(userId)
                        toSend.add(userId)
                    }
                }
                if (toSend.isNotEmpty()) onFlush(guildId, toSend.toList())
                pendingRequests.clear()
            }

            /** @return whether anything was re-queued. */
            fun requestUnacknowledged(): Boolean {
                if (unacknowledgedRequests.isEmpty()) return false
                val iterator = unacknowledgedRequests.iterator()
                while (iterator.hasNext()) {
                    val userId = iterator.next()
                    if (guildMemberExists(userId)) iterator.remove() else pendingRequests.add(userId)
                }
                return pendingRequests.isNotEmpty()
            }
        }
    }
}
