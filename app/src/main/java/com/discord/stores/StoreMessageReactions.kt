package com.discord.stores

import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.api.message.reaction.MessageReactionRemoveAll
import com.discord.api.message.reaction.MessageReactionRemoveEmoji
import com.discord.api.message.reaction.MessageReactionUpdate
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable
import java.util.concurrent.TimeUnit

/**
 * Who reacted with what — the user list behind a reaction pill, not the counts.
 *
 * The counts shown on the pill come from `Message.reactions` and are maintained by
 * [StoreMessages]. This store owns the separate, lazily fetched question "which users are behind
 * this emoji", which the reaction-details sheet asks for. Keeping the two apart matters: counts
 * arrive with every message and must never block on a REST call, while the user list is fetched
 * once per emoji and has its own loading and failure states.
 *
 * Results are keyed `messageId -> emojiKey -> `[EmojiResults]. Fetches are started by
 * [observeMessageReactions] itself, so a caller only has to subscribe.
 */
class StoreMessageReactions @JvmOverloads constructor(
    private val dispatcher: Dispatcher,
    private val userStore: StoreUser,
    private val observationDeck: ObservationDeck,
    private val api: () -> RestAPIInterface? = { null },
) : StoreV2() {

    /** The state of the "who reacted" query for one emoji on one message. */
    sealed class EmojiResults {
        /** A fetch is in flight, or has not started yet. */
        object Loading : EmojiResults()

        /**
         * The fetch failed. Carries the request coordinates so the UI's retry button does not
         * have to remember them.
         */
        data class Failure(
            val channelId: Long,
            val messageId: Long,
            val emoji: MessageReactionEmoji,
        ) : EmojiResults()

        /**
         * The reacting users, in the server's order.
         *
         * A [LinkedHashMap] rather than a list because gateway reaction add/remove events mutate
         * it in place by user id while preserving the original ordering; [deepCopy] is what makes
         * that safe to publish, see [snapshotData].
         */
        data class Users(
            val users: LinkedHashMap<Long, User>,
            val channelId: Long,
            val messageId: Long,
            val emoji: MessageReactionEmoji,
        ) : EmojiResults() {
            fun deepCopy() = Users(LinkedHashMap(users), channelId, messageId, emoji)
        }
    }

    private val reactions = HashMap<Long, MutableMap<String, EmojiResults>>()
    private var reactionsSnapshot: Map<Long, Map<String, EmojiResults>> = emptyMap()

    /** `messageId:emoji` pairs with a request in flight, so rebinding a row never sends a second. */
    private val fetching = HashSet<String>()

    /**
     * Background fetches for the reaction chips, run one at a time with a pause between them, as
     * Vencord's WhoReacted does. Newest first, so the rows just scrolled into view load before
     * ones long gone. A key leaves [queuedKeys] when it starts, or when the sheet takes it over.
     */
    private val fetchQueue = ArrayDeque<QueuedFetch>()
    private val queuedKeys = HashSet<String>()

    /** Request keys of user lists kept from before a reconnect, shown until they are fetched again. */
    private val stale = HashSet<String>()
    private var queueBusy = false

    private data class QueuedFetch(
        val key: String,
        val channelId: Long,
        val messageId: Long,
        val emoji: MessageReactionEmoji,
        val burst: Boolean,
    )

    /**
     * The reacting users for one emoji, fetching them on first subscription. With [burst], the
     * ones who super reacted with it, which Discord lists apart from the normal reactions.
     *
     * Emits [EmojiResults.Loading] immediately so the sheet can render a spinner without a
     * separate "has a request started" flag. [queued] callers wait their turn in the background
     * queue instead of fetching at once.
     */
    fun observeMessageReactions(
        channelId: Long,
        messageId: Long,
        emoji: MessageReactionEmoji,
        queued: Boolean = false,
        burst: Boolean = false,
    ): Observable<EmojiResults> {
        dispatcher.schedule { fetchReactions(channelId, messageId, emoji, queued, burst) }
        return observationDeck.connectRx(this) {
            reactionsSnapshot[messageId]?.get(listKey(emoji, burst)) ?: EmojiResults.Loading
        }.distinctUntilChanged()
    }

    /** Discards any cached result for this emoji and fetches again, for the retry affordance. */
    fun forceRetryFetchReactions(channelId: Long, messageId: Long, emoji: MessageReactionEmoji) =
        dispatcher.schedule {
            reactions[messageId]?.remove(emoji.reactionKey)
            fetchReactions(channelId, messageId, emoji, queued = false)
            markChanged()
        }

    /** Removes [userId]'s reaction. The store is updated by the resulting gateway event. */
    fun deleteEmoji(channelId: Long, messageId: Long, emoji: MessageReactionEmoji, userId: Long, burst: Boolean = false) {
        val restApi = api() ?: return
        val type = if (burst) MessageReaction.TYPE_BURST else MessageReaction.TYPE_NORMAL
        restApi.removeReaction(channelId, messageId, getReactionEmojiRequestParam(emoji), userId, type)
            .restSubscribeOn()
            .appSubscribe(errorClass = StoreMessageReactions::class.java) { }
    }

    /**
     * Marks every cached user list stale on reconnect.
     *
     * Reactions added while the socket was down are not replayed, so each list is fetched again
     * the next time it is looked at. Until the fresh one arrives the old one stays up: the people
     * have usually not changed, and blanking every chip's avatars for a moment reads as a flicker.
     */
    @StoreThread
    fun handleConnectionOpen() {
        clearQueue()
        reactions.forEach { (messageId, lists) ->
            lists.entries.removeAll { it.value !is EmojiResults.Users }
            lists.keys.forEach { stale += "$messageId:$it" }
        }
        markChanged()
    }

    /**
     * Folds a reaction add into an already-loaded user list.
     *
     * Nothing is done when the list has not been fetched, or when the reacting user is unknown:
     * inserting a placeholder would render a blank row, and the correct list arrives with the
     * next fetch anyway.
     */
    @StoreThread
    fun handleReactionAdd(update: MessageReactionUpdate) {
        val user = userStore.getUsersInternal()[update.userId] ?: return
        val results = ensureReactionResults(update.messageId, listKey(update.emoji, update.isBurst))
        if (results is EmojiResults.Users) {
            results.users[update.userId] = user
            markChanged()
        }
    }

    @StoreThread
    fun handleReactionRemove(update: MessageReactionUpdate) {
        val results = ensureReactionResults(update.messageId, listKey(update.emoji, update.isBurst))
        if (results is EmojiResults.Users) {
            results.users.remove(update.userId)
            markChanged()
        }
    }

    @StoreThread
    fun handleReactionRemoveAll(update: MessageReactionRemoveAll) = handleReactionRemoveAll(update.messageId)

    @StoreThread
    fun handleReactionRemoveAll(messageId: Long) {
        reactions.remove(messageId)
        markChanged()
    }

    @StoreThread
    fun handleReactionRemoveEmoji(update: MessageReactionRemoveEmoji) =
        handleReactionRemoveEmoji(update.messageId, update.emoji)

    @StoreThread
    fun handleReactionRemoveEmoji(messageId: Long, emoji: MessageReactionEmoji) {
        ensureReactionResults(messageId, emoji.reactionKey)
        reactions[messageId]?.remove(emoji.reactionKey)
        reactions[messageId]?.remove(listKey(emoji, burst = true))
        markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        clearQueue()
        stale.clear()
        if (reactions.isNotEmpty()) {
            reactions.clear()
            markChanged()
        }
    }

    /**
     * Fetches the user list unless one is already loaded and non-empty, already being fetched, or
     * failed.
     *
     * The "non-empty" part is deliberate: an empty [EmojiResults.Users] means the last reactor
     * just removed their reaction, and re-fetching is how the list recovers if it comes back.
     * Every reaction chip subscribes on each bind, so neither an in-flight request nor a failure
     * (often a rate limit) is repeated here; the sheet retries through [forceRetryFetchReactions].
     */
    @StoreThread
    private fun fetchReactions(
        channelId: Long,
        messageId: Long,
        emoji: MessageReactionEmoji,
        queued: Boolean,
        burst: Boolean = false,
    ) {
        val existing = ensureReactionResults(messageId, listKey(emoji, burst))
        val requestKey = "$messageId:${listKey(emoji, burst)}"
        if (existing is EmojiResults.Users && existing.users.isNotEmpty() && requestKey !in stale) return
        if (existing is EmojiResults.Failure) return
        if (requestKey in fetching) return
        if (queued) {
            if (queuedKeys.add(requestKey)) {
                fetchQueue.addFirst(QueuedFetch(requestKey, channelId, messageId, emoji, burst))
                drainQueue()
            }
            return
        }
        queuedKeys -= requestKey
        startFetch(requestKey, channelId, messageId, emoji, burst) {}
    }

    @StoreThread
    private fun drainQueue() {
        if (queueBusy) return
        while (true) {
            val next = fetchQueue.removeFirstOrNull() ?: return
            if (!queuedKeys.remove(next.key)) continue
            queueBusy = true
            startFetch(next.key, next.channelId, next.messageId, next.emoji, next.burst) {
                Observable.timer(QUEUE_GAP_MS, TimeUnit.MILLISECONDS).subscribe {
                    dispatcher.schedule {
                        queueBusy = false
                        drainQueue()
                    }
                }
            }
            return
        }
    }

    @StoreThread
    private fun clearQueue() {
        fetchQueue.clear()
        queuedKeys.clear()
    }

    /** [onTerminated] runs on whichever thread the request ends on. */
    @StoreThread
    private fun startFetch(
        requestKey: String,
        channelId: Long,
        messageId: Long,
        emoji: MessageReactionEmoji,
        burst: Boolean,
        onTerminated: () -> Unit,
    ) {
        val restApi = api() ?: return onTerminated()
        fetching += requestKey
        // A stale list keeps showing while its replacement loads.
        if (reactions[messageId]?.get(listKey(emoji, burst)) !is EmojiResults.Users) {
            reactions[messageId]?.put(listKey(emoji, burst), EmojiResults.Loading)
        }

        restApi.getReactionUsers(
            channelId,
            messageId,
            getReactionEmojiRequestParam(emoji),
            REACTION_USER_PAGE_SIZE,
            MessageReaction.TYPE_BURST.takeIf { burst },
        )
            .restSubscribeOn()
            .appSubscribe(
                errorClass = StoreMessageReactions::class.java,
                onTerminated = onTerminated,
                errorHandler = {
                    dispatcher.schedule {
                        fetching -= requestKey
                        handleLoadReactionUsersFailure(channelId, messageId, emoji, burst)
                    }
                },
                onNext = { apiUsers ->
                    val users = apiUsers.map(::CoreUser)
                    dispatcher.schedule {
                        fetching -= requestKey
                        handleReactionUsers(channelId, messageId, emoji, users, burst)
                    }
                },
            )
    }

    @StoreThread
    private fun handleLoadReactionUsersFailure(
        channelId: Long,
        messageId: Long,
        emoji: MessageReactionEmoji,
        burst: Boolean,
    ) {
        val existing = ensureReactionResults(messageId, listKey(emoji, burst))
        // A refresh that failed leaves the list it was refreshing; it is only stale, not wrong.
        if (stale.remove("$messageId:${listKey(emoji, burst)}") && existing is EmojiResults.Users) return
        reactions[messageId]?.put(listKey(emoji, burst), EmojiResults.Failure(channelId, messageId, emoji))
        markChanged()
    }

    @StoreThread
    private fun handleReactionUsers(
        channelId: Long,
        messageId: Long,
        emoji: MessageReactionEmoji,
        reactionUsers: List<User>,
        burst: Boolean,
    ) {
        ensureReactionResults(messageId, listKey(emoji, burst))
        stale -= "$messageId:${listKey(emoji, burst)}"
        val users = LinkedHashMap<Long, User>()
        reactionUsers.forEach { users[it.id] = it }
        reactions[messageId]?.put(listKey(emoji, burst), EmojiResults.Users(users, channelId, messageId, emoji))
        markChanged()
    }

    /** Super reactions are listed apart from normal ones, so each has its own entry. */
    private fun listKey(emoji: MessageReactionEmoji, burst: Boolean): String =
        if (burst) "${emoji.reactionKey}$BURST_SUFFIX" else emoji.reactionKey

    /** Reads the current entry, seeding [EmojiResults.Loading] if the emoji is not tracked yet. */
    @StoreThread
    private fun ensureReactionResults(messageId: Long, emoji: String): EmojiResults {
        val forMessage = reactions.getOrPut(messageId) { HashMap() }
        return forMessage.getOrPut(emoji) { EmojiResults.Loading }
    }

    /**
     * The path segment identifying an emoji in the reactions API.
     *
     * Custom emoji are `name:id`; unicode emoji are the literal character.
     */
    private fun getReactionEmojiRequestParam(emoji: MessageReactionEmoji): String =
        if (emoji.isCustom) "${emoji.name}:${emoji.id}" else emoji.name.orEmpty()

    /**
     * Publishes a deep copy.
     *
     * [EmojiResults.Users.users] is mutated in place by the gateway handlers above, so a shallow
     * copy would let a subscriber iterate the same map the store thread is writing to.
     */
    @StoreThread
    override fun snapshotData() {
        reactionsSnapshot = reactions.mapValues { (_, byEmoji) ->
            byEmoji.mapValues { (_, results) ->
                if (results is EmojiResults.Users) results.deepCopy() else results
            }
        }
    }

    private companion object {
        /** Matches the reference's single-page fetch; the sheet does not paginate further. */
        const val REACTION_USER_PAGE_SIZE = 100

        /** Vencord's pause between WhoReacted fetches. */
        const val QUEUE_GAP_MS = 250L

        const val BURST_SUFFIX = "#burst"
    }
}
