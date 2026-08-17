package com.discord.stores

import androidx.recyclerview.widget.LinearLayoutManager
import com.discord.models.message.Message
import com.discord.models.user.User
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.rx.filterNull
import rx.Observable
import rx.subjects.PublishSubject

/**
 * The chat surface's own state: what the message list is currently showing, what is being
 * edited, which blocked-message groups are expanded, and one-shot text events for the input.
 *
 * Three responsibilities live here because the reference keeps them together, and because two
 * of them are only meaningful in combination:
 *
 * * [InteractionState] is the *only* description of where the message list is scrolled.
 *   [StoreMessagesLoader] subscribes to it and derives "load older" / "load newer" from
 *   [InteractionState.isAtTop] / [InteractionState.isAtBottom]; without it infinite scroll
 *   cannot happen at all.
 * * [EditingMessage] carries both the message and the *in-progress edit text*, so rotating the
 *   device or backgrounding the app does not silently drop what was typed.
 * * [Event] is a one-shot bus, not state: "insert @user into the composer" must not replay to a
 *   later subscriber, which is why it is a [PublishSubject] rather than a snapshot field.
 */
class StoreChat @JvmOverloads constructor(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    /**
     * Asks the gateway to stream members matching a mention query.
     *
     * The reference calls `StoreGatewayConnection.requestGuildMembers(guildId, username)` inline
     * from [appendMention] so the mentioned user is resolvable by the time the message is sent.
     * `StoreStream` owns the socket, so it is injected rather than reached for statically.
     */
    private val requestGuildMembers: (guildId: Long, query: String) -> Unit = { _, _ -> },
) : StoreV2() {

    /** A message being edited, together with the text currently in the composer. */
    data class EditingMessage(val message: Message, val content: CharSequence)

    /** One-shot instructions for the chat input. Never replayed. */
    sealed class Event {
        /** Append [text] at the composer's cursor, e.g. a mention inserted from the member list. */
        data class AppendChatText(val text: String) : Event()

        /** Replace the composer's whole contents with [text], e.g. when recalling a command. */
        data class ReplaceChatText(val text: String) : Event()
    }

    /**
     * Where the chat list is scrolled, for [channelId], as of the last scroll or layout pass.
     *
     * [scrollState] is a bit set rather than an enum because "at the bottom" and "near the
     * bottom" are not mutually exclusive. The chat list is oldest-first — adapter position 0
     * is the start of chat, at the top of the screen — which is why [Companion.getScrollState]
     * reads *last* visible position to decide "at bottom" and *first* visible position to
     * decide "at top".
     *
     * [isAtTop] and [isAtBottom] are additionally gated on [isTouchedSinceLastJump]: right after
     * a jump-to-message the list is programmatically positioned, and auto-loading from that
     * position would immediately undo the jump. Only once the user has actually touched the list
     * do the edges start driving pagination. [isAtTopIgnoringTouch] and friends expose the
     * ungated values for callers (such as auto-ack) that want raw position.
     */
    data class InteractionState(
        val channelId: Long,
        val lastMessageId: Long,
        val isTouchedSinceLastJump: Boolean,
        private val scrollState: Int,
    ) {
        constructor(
            channelId: Long,
            lastMessageId: Long,
            isTouchedSinceLastJump: Boolean,
            layoutManager: LinearLayoutManager?,
        ) : this(channelId, lastMessageId, isTouchedSinceLastJump, getScrollState(layoutManager))

        val isAtTop: Boolean = isTouchedSinceLastJump && isAtTopIgnoringTouch()
        val isAtBottom: Boolean = isTouchedSinceLastJump && isAtBottomIgnoringTouch()
        val isNearBottom: Boolean = isTouchedSinceLastJump && isNearBottomIgnoringTouch()

        fun isAtBottomIgnoringTouch(): Boolean = (scrollState and SCROLLED_BOTTOM) != 0
        fun isAtTopIgnoringTouch(): Boolean = (scrollState and SCROLLED_TOP) != 0
        fun isNearBottomIgnoringTouch(): Boolean = (scrollState and SCROLLED_NEAR_BOTTOM) != 0

        companion object {
            /** How many rows from an edge still counts as "at" that edge. */
            private const val NEAR_EDGE_THRESHOLD = 15

            const val SCROLLED = 0
            const val SCROLLED_BOTTOM = 1
            const val SCROLLED_TOP = 2
            const val SCROLLED_NEAR_BOTTOM = 4

            /**
             * Oldest-first list: first visible at 0 is the top, last visible at the end is the bottom.
             */
            @JvmStatic
            fun getScrollState(layoutManager: LinearLayoutManager?): Int {
                if (layoutManager == null) return SCROLLED
                val lastIndex = layoutManager.itemCount - 1
                val firstVisible = layoutManager.findFirstVisibleItemPosition()
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val bottomThreshold = maxOf(0, lastIndex - NEAR_EDGE_THRESHOLD)

                var state = if (lastVisible >= lastIndex) SCROLLED_BOTTOM else SCROLLED
                if (firstVisible <= 0) state = state or SCROLLED_TOP
                if (lastVisible >= bottomThreshold && lastVisible < lastIndex) {
                    state = state or SCROLLED_NEAR_BOTTOM
                }
                return state
            }
        }
    }

    private val eventSubject = PublishSubject.create<Event>().toSerialized()
    private val expandedBlockedMessageGroups = ArrayList<Long>()

    private var editingMessage: EditingMessage? = null
    private var interactionState: InteractionState? = null
    private var expandedBlockedMessageGroupsSnapshot: List<Long> = emptyList()

    fun getEditingMessage(): EditingMessage? = editingMessage
    fun getInteractionState(): InteractionState? = interactionState
    fun getExpandedBlockedMessageGroups(): List<Long> = expandedBlockedMessageGroupsSnapshot

    fun observeEditingMessage(): Observable<EditingMessage?> =
        observationDeck.connectRx(this) { getEditingMessage() }.distinctUntilChanged()

    fun observeEvents(): Observable<Event> = eventSubject

    fun observeExpandedBlockedMessageIds(): Observable<List<Long>> =
        observationDeck.connectRx(this) { getExpandedBlockedMessageGroups() }.distinctUntilChanged()

    /**
     * Emits only once a real interaction state exists.
     *
     * The reference filters nulls out rather than emitting a placeholder, because
     * `StoreMessagesLoader` must not treat "no list has ever been laid out" as "scrolled to the
     * top of an empty list" and start paginating.
     */
    fun observeInteractionState(): Observable<InteractionState> =
        observationDeck.connectRx<InteractionState?>(this) { getInteractionState() }
            .filterNull()
            .distinctUntilChanged()

    fun setEditingMessage(editingMessage: EditingMessage?) = dispatcher.schedule {
        this.editingMessage = editingMessage
        markChanged()
    }

    fun setInteractionState(interactionState: InteractionState) = dispatcher.schedule {
        this.interactionState = interactionState
        markChanged()
    }

    fun toggleBlockedMessageGroup(messageId: Long) = dispatcher.schedule {
        if (expandedBlockedMessageGroups.contains(messageId)) {
            expandedBlockedMessageGroups.remove(messageId)
        } else {
            expandedBlockedMessageGroups.add(messageId)
        }
        markChanged()
    }

    /** Inserts the canonical mention token and warms the member cache for local rendering. */
    fun appendMention(user: User?, guildId: Long) {
        if (user == null) return
        if (user.username.isNotEmpty()) requestGuildMembers(guildId, user.username)
        eventSubject.onNext(Event.AppendChatText("<@${user.id}> "))
    }

    fun replaceChatText(text: String) = eventSubject.onNext(Event.ReplaceChatText("$text "))

    @StoreThread
    fun handlePreLogout() {
        editingMessage = null
        interactionState = null
        expandedBlockedMessageGroups.clear()
        markChanged()
    }

    override fun snapshotData() {
        expandedBlockedMessageGroupsSnapshot = ArrayList(expandedBlockedMessageGroups)
    }
}
