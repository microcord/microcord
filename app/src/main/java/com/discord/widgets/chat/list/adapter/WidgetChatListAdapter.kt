package com.discord.widgets.chat.list.adapter

import android.content.Context
import android.graphics.drawable.TransitionDrawable
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.activity.Activity
import com.discord.api.application.Application
import com.discord.api.channel.Channel
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.message.activity.MessageActivityType
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.sticker.BaseSticker
import com.discord.api.sticker.Sticker
import com.discord.app.AppFragment
import com.discord.app.AppPermissionsRequests
import com.discord.models.guild.Guild
import com.discord.models.message.Message
import com.discord.stores.StoreChat
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.widgets.botuikit.ComponentProvider
import com.discord.widgets.chat.list.FragmentLifecycleListener
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.list.entries.NewMessagesEntry
import com.discord.widgets.chat.list.model.WidgetChatListModel
import java.lang.ref.WeakReference

/**
 * The chat list.
 *
 * The adapter owns three concerns beyond turning [ChatListEntry] rows into views:
 *
 *  * **Interaction reporting.** Scroll position and whether the user has touched the list since the
 *    last programmatic jump are published back to [StoreChat] on every scroll, update and touch.
 *    `StoreMessagesLoader` drives pagination off that signal, so a list that never publishes never
 *    paginates.
 *  * **Jump-to-message.** [scrollToMessageId] retries until the target row exists (history may
 *    still be loading), then flashes it.
 *  * **Row lifecycle.** Inline media players are started and stopped through [onResume]/[onPause]
 *    rather than left running when the screen is not visible.
 */
class WidgetChatListAdapter @JvmOverloads constructor(
    recycler: RecyclerView,
    val appPermissionsRequests: AppPermissionsRequests,
    var fragmentManager: FragmentManager,
    val eventHandler: EventHandler,
    val clock: Clock = ClockFactory.get(),
) : MGRecyclerAdapterSimple<ChatListEntry>(recycler) {

    val botUiComponentProvider = ComponentProvider(recycler.context)

    /**
     * The whole state the rows render from. Assigning it also pushes the row list through the
     * differ, so callers only ever set this one property.
     */
    var data: Data = EmptyData()
        set(value) {
            field = value
            setData(value.list)
        }

    /**
     * Whether a message that mentions the viewer paints its highlight background. Off for surfaces
     * such as pinned messages and search results, where every row would otherwise light up.
     */
    var mentionMeMessageLevelHighlighting: Boolean = true

    private val handlerOfTouches = HandlerOfTouches()
    private val handlerOfScrolls = HandlerOfScrolls()
    private val handlerOfUpdates = HandlerOfUpdates()

    private var isTouchedSinceLastJump: Boolean = false
    private var scrollToWithHighlight: ScrollToWithHighlight? = null

    val layoutManager: LinearLayoutManager?
        get() = recycler.layoutManager as? LinearLayoutManager

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<WidgetChatListAdapter, ChatListEntry> = when (viewType) {
        ChatListEntry.MESSAGE, ChatListEntry.REPLY ->
            WidgetChatListAdapterItemMessage(R.layout.widget_chat_list_adapter_item_text, this)
        ChatListEntry.MESSAGE_MINIMAL ->
            WidgetChatListAdapterItemMessage(R.layout.widget_chat_list_adapter_item_minimal, this)
        ChatListEntry.LOAD_INDICATOR ->
            WidgetChatListItem(R.layout.widget_chat_list_adapter_item_loading, this)
        ChatListEntry.START_OF_CHAT -> WidgetChatListAdapterItemStart(this)
        ChatListEntry.REACTIONS -> WidgetChatListAdapterItemReactions(this)
        ChatListEntry.SYSTEM_MESSAGE -> WidgetChatListAdapterItemSystemMessage(this)
        ChatListEntry.UPLOAD_STATUS -> WidgetChatListAdapterItemUploadProgress(this)
        ChatListEntry.SPACER ->
            WidgetChatListItem(R.layout.widget_chat_list_adapter_item_spacer, this)
        ChatListEntry.NEW_MESSAGES -> WidgetChatListAdapterItemNewMessages(this)
        ChatListEntry.TIMESTAMP -> WidgetChatListAdapterItemTimestamp(this)
        ChatListEntry.BLOCKED_MESSAGES -> WidgetChatListAdapterItemBlocked(this)
        ChatListEntry.SEARCH_RESULT_COUNT -> WidgetChatListAdapterItemSearchResultCount(this)
        ChatListEntry.SEARCH_INDEXING ->
            MGRecyclerViewHolder(R.layout.widget_chat_list_adapter_item_search_indexing, this)
        ChatListEntry.SEARCH_EMPTY ->
            MGRecyclerViewHolder(R.layout.widget_chat_list_adapter_item_search_empty, this)
        ChatListEntry.SEARCH_ERROR ->
            MGRecyclerViewHolder(R.layout.widget_chat_list_adapter_item_search_error, this)
        ChatListEntry.DIVIDER ->
            MGRecyclerViewHolder(R.layout.widget_chat_list_adapter_item_divider, this)
        ChatListEntry.EMPTY_PINS -> WidgetChatListAdapterItemEmptyPins(this)
        ChatListEntry.MESSAGE_HEADER -> WidgetChatListAdapterItemMessageHeader(this)
        ChatListEntry.MENTION_FOOTER -> WidgetChatListAdapterItemMentionFooter(this)
        ChatListEntry.CALL_MESSAGE -> WidgetChatListAdapterItemCallMessage(this)
        ChatListEntry.MESSAGE_FAILED ->
            WidgetChatListAdapterItemMessage(R.layout.widget_chat_list_adapter_item_failed, this)
        ChatListEntry.MESSAGE_EMBED -> WidgetChatListAdapterItemEmbed(this)
        ChatListEntry.GAME_INVITE -> WidgetChatListAdapterItemGameInvite(this)
        ChatListEntry.SPOTIFY_LISTEN_TOGETHER -> WidgetChatListAdapterItemSpotifyListenTogether(this)
        ChatListEntry.INVITE -> WidgetChatListAdapterItemInvite(this)
        ChatListEntry.GUILD_WELCOME -> WidgetChatListAdapterItemGuildWelcome(this)
        ChatListEntry.GIFT -> WidgetChatListAdapterItemGift(this)
        ChatListEntry.MESSAGE_ATTACHMENT -> WidgetChatListAdapterItemAttachment(this)
        ChatListEntry.START_OF_PRIVATE_CHAT -> WidgetChatListAdapterItemPrivateChannelStart(this)
        ChatListEntry.GUILD_TEMPLATE -> WidgetChatListAdapterItemGuildTemplate(this)
        ChatListEntry.STICKER -> WidgetChatListAdapterItemSticker(this)
        ChatListEntry.APPLICATION_COMMAND -> WidgetChatListAdapterItemApplicationCommand(this)
        ChatListEntry.EPHEMERAL_MESSAGE -> WidgetChatListAdapterItemEphemeralMessage(this)
        ChatListEntry.THREAD_EMBED -> WidgetChatListAdapterItemThreadEmbed(this)
        ChatListEntry.BOT_UI_COMPONENT -> WidgetChatListAdapterItemBotComponentRow(this)
        ChatListEntry.THREAD_DRAFT_FORM -> WidgetChatListAdapterItemThreadDraftForm(this)
        ChatListEntry.GUILD_INVITE_REMINDER -> WidgetChatListAdapterItemGuildInviteReminder(this)
        ChatListEntry.STAGE_INVITE -> WidgetChatListAdapterItemStageInvite(this)
        ChatListEntry.THREAD_STARTER_DIVIDER ->
            MGRecyclerViewHolder(R.layout.widget_chat_list_adapter_item_thread_starter_divider, this)
        ChatListEntry.STICKER_GREET -> WidgetChatListAdapterItemStickerGreet(this)
        ChatListEntry.STICKER_GREET_COMPACT -> WidgetChatListAdapterItemStickerGreetCompact(this)
        ChatListEntry.GUILD_SCHEDULED_EVENT_INVITE ->
            WidgetChatListAdapterItemGuildScheduledEventInvite(this)
        ChatListEntry.AUTOMOD_BLOCKED -> WidgetChatListAdapterItemAutoModBlocked(this)
        ChatListEntry.AUTOMOD_SYSTEM_MESSAGE -> WidgetChatListAdapterItemAutoModSystemMessageEmbed(this)
        else -> throw invalidViewTypeException(viewType)
    }

    // region lifecycle

    /**
     * Installs the touch, scroll and update handlers. Called from the host's `onResume`, because
     * the handlers publish into a store and must not keep doing so while the screen is away.
     */
    fun setHandlers() {
        setOnUpdated { _, _ ->
            recycler.removeCallbacks(handlerOfUpdates)
            recycler.post(handlerOfUpdates)
            recycler.setOnTouchListener(handlerOfTouches)
            recycler.removeOnScrollListener(handlerOfScrolls)
            recycler.addOnScrollListener(handlerOfScrolls)
        }
    }

    /**
     * Detaches everything [setHandlers] installed and publishes a final interaction state with a
     * null layout manager, which reads as "no position known" rather than "scrolled to the top" —
     * the difference between the loader idling and the loader paginating into a dead screen.
     */
    fun disposeHandlers() {
        setOnUpdated { _, _ -> }
        recycler.removeCallbacks(handlerOfUpdates)
        recycler.setOnTouchListener(null)
        recycler.removeOnScrollListener(handlerOfScrolls)
        eventHandler.onInteractionStateUpdated(
            StoreChat.InteractionState(data.channelId, 0L, isTouchedSinceLastJump, null),
        )
    }

    /** Forwards to every bound row that cares, so inline players stop with the screen. */
    fun onPause() = forEachVisibleLifecycleListener(FragmentLifecycleListener::onPause)

    fun onResume() = forEachVisibleLifecycleListener(FragmentLifecycleListener::onResume)

    private fun forEachVisibleLifecycleListener(action: (FragmentLifecycleListener) -> Unit) {
        repeat(recycler.childCount) { index ->
            val holder = recycler.getChildViewHolder(recycler.getChildAt(index))
            if (holder is FragmentLifecycleListener) action(holder)
        }
    }

    // endregion

    // region jump to message

    /**
     * Scrolls to [messageId], flashes it, then calls [onCompleted].
     *
     * A jump that is already running for a real message wins over a later "jump to newest"
     * (`messageId <= 0`) request: the newest-message scroll is issued on every channel change and
     * would otherwise cancel the jump the user explicitly asked for.
     */
    fun scrollToMessageId(messageId: Long, onCompleted: () -> Unit) {
        val supersededByJump = scrollToWithHighlight.let { it != null && it.messageId > 0L } &&
            messageId <= 0L
        if (supersededByJump) {
            onCompleted()
            return
        }
        isTouchedSinceLastJump = false
        scrollToWithHighlight?.cancel()
        scrollToWithHighlight = ScrollToWithHighlight(this, messageId) {
            scrollToWithHighlight = null
            publishInteractionState()
            onCompleted()
        }
    }

    // endregion

    // region event fan-out
    // The rows know their own entry but not the surrounding channel/guild/viewer, so the adapter
    // completes each callback from `data` before handing it to the host's EventHandler.

    fun onQuickAddReactionClicked(messageId: Long) {
        eventHandler.onQuickAddReactionClicked(data.guildId, data.userId, data.channelId, messageId)
    }

    fun onReactionClicked(messageId: Long, reaction: MessageReaction, canAddReactions: Boolean) {
        eventHandler.onReactionClicked(
            data.guildId,
            data.userId,
            data.channelId,
            messageId,
            reaction,
            canAddReactions,
        )
    }

    fun onReactionLongClicked(messageId: Long, reaction: MessageReaction) {
        eventHandler.onReactionLongClicked(data.guildId, data.channelId, messageId, reaction)
    }

    fun onBotUiComponentClicked(
        applicationId: Long,
        messageId: Long,
        messageFlags: Long?,
        componentIndex: Int,
        customId: String?,
        values: List<String>?,
    ) {
        eventHandler.onBotUiComponentClicked(
            applicationId,
            data.guild?.id,
            data.channelId,
            messageId,
            messageFlags,
            componentIndex,
            customId,
            values,
        )
    }

    fun onRoleSubscriptionPurchaseSystemMessageClicked(roleSubscriptionListingId: Long, messageId: Long) {
        eventHandler.onRoleSubscriptionPurchaseTierClick(
            roleSubscriptionListingId,
            data.guildId,
            data.userId,
            data.channelId,
            messageId,
        )
    }

    fun onShareButtonClick(
        guildEvent: GuildScheduledEvent,
        weakContext: WeakReference<Context>,
        weakFragment: WeakReference<AppFragment>,
    ) = eventHandler.onShareButtonClick(guildEvent, weakContext, weakFragment)

    fun onStickerClicked(message: Message, sticker: BaseSticker) =
        eventHandler.onStickerClicked(message, sticker)

    fun onThreadClicked(channel: Channel) = eventHandler.onThreadClicked(channel)

    fun onThreadLongClicked(channel: Channel) = eventHandler.onThreadLongClicked(channel)

    fun onUserActivityAction(
        authorId: Long,
        messageId: Long,
        messageActivityType: MessageActivityType,
        activity: Activity,
        application: Application,
    ) = eventHandler.onUserActivityAction(
        authorId,
        data.channelId,
        messageId,
        messageActivityType,
        activity,
        application,
    )

    // endregion

    /**
     * Publishes where the list is now. [WidgetChatListModel.newestKnownMessageId] is only available
     * on the real model — the pins and search surfaces use a different [Data] and report 0, which
     * reads as "nothing newer to load".
     */
    private fun publishInteractionState() {
        val newestKnownMessageId = (data as? WidgetChatListModel)?.newestKnownMessageId ?: 0L
        eventHandler.onInteractionStateUpdated(
            StoreChat.InteractionState(
                data.channelId,
                newestKnownMessageId,
                isTouchedSinceLastJump,
                layoutManager,
            ),
        )
    }

    /** Everything the rows need that is not carried by the row itself. */
    interface Data {
        val channelId: Long
        val channelNames: Map<Long, String>
        val guild: Guild?
        val guildId: Long
        val list: List<ChatListEntry>
        val myRoleIds: Set<Long>
        val newMessagesMarkerMessageId: Long
        val oldestMessageId: Long
        val userId: Long

        /** False on read-only surfaces (pins, search) where tapping a spoiler must not reveal it. */
        val isSpoilerClickAllowed: Boolean
    }

    /** What the adapter renders before its first real assignment. */
    private class EmptyData : Data {
        override val channelId: Long = 0L
        override val channelNames: Map<Long, String> = emptyMap()
        override val guild: Guild? = null
        override val guildId: Long = 0L
        override val list: List<ChatListEntry> = emptyList()
        override val myRoleIds: Set<Long> = emptySet()
        override val newMessagesMarkerMessageId: Long = 0L
        override val oldestMessageId: Long = 0L
        override val userId: Long = 0L
        override val isSpoilerClickAllowed: Boolean = false
    }

    /**
     * Everything a chat row can ask its host to do.
     *
     * Every member has a no-op default so a surface implements only what it supports — the pinned
     * messages list, for instance, wires long-press and unpin but nothing about threads.
     */
    interface EventHandler {
        fun onBotUiComponentClicked(
            applicationId: Long,
            guildId: Long?,
            channelId: Long,
            messageId: Long,
            messageFlags: Long?,
            componentIndex: Int,
            customId: String?,
            values: List<String>?,
        ) = Unit

        fun onCallMessageClicked(voiceChannelId: Long, callStatus: WidgetChatListAdapterItemCallMessage.CallStatus) = Unit

        fun onCommandClicked(
            interactionId: Long,
            guildId: Long?,
            channelId: Long,
            messageId: Long,
            interactionUserId: Long,
            applicationId: Long,
            messageNonce: String?,
        ) = Unit

        fun onDismissClicked(message: Message) = Unit

        fun onDismissLocalMessageClicked(message: Message) = Unit

        fun onInteractionStateUpdated(interactionState: StoreChat.InteractionState) = Unit

        fun onListClicked() = Unit

        fun onMessageAuthorAvatarClicked(message: Message, guildId: Long) = Unit

        fun onMessageAuthorLongClicked(message: Message, guildId: Long?) = Unit

        fun onMessageAuthorNameClicked(message: Message, guildId: Long) = Unit

        fun onMessageBlockedGroupClicked(message: Message) = Unit

        fun onMessageClicked(message: Message, isThreadStarterMessage: Boolean) = Unit

        fun onMessageLongClicked(
            message: Message,
            formattedMessage: CharSequence,
            isThreadStarterMessage: Boolean,
        ) = Unit

        fun onOldestMessageId(channelId: Long, oldestMessageId: Long) = Unit

        fun onOpenPinsClicked(message: Message) = Unit

        fun onQuickAddReactionClicked(guildId: Long, myUserId: Long, channelId: Long, messageId: Long) = Unit

        fun onQuickDownloadClicked(uri: Uri, fileName: String): Boolean = false

        fun onReactionClicked(
            guildId: Long,
            myUserId: Long,
            channelId: Long,
            messageId: Long,
            reaction: MessageReaction,
            canAddReactions: Boolean,
        ) = Unit

        fun onReactionLongClicked(guildId: Long, channelId: Long, messageId: Long, reaction: MessageReaction) = Unit

        fun onReportIssueWithAutoMod(context: Context, message: Message) = Unit

        fun onRoleSubscriptionPurchaseTierClick(
            roleSubscriptionListingId: Long,
            guildId: Long,
            userId: Long,
            channelId: Long,
            messageId: Long,
        ) = Unit

        fun onSendGreetMessageClicked(channelId: Long, channelType: Int, sticker: Sticker) = Unit

        fun onShareButtonClick(
            guildEvent: GuildScheduledEvent,
            weakContext: WeakReference<Context>,
            weakFragment: WeakReference<AppFragment>,
        ) = Unit

        fun onStickerClicked(message: Message, sticker: BaseSticker) = Unit

        fun onSystemMessageCtaClicked(message: Message, channel: Channel, sticker: BaseSticker) = Unit

        fun onThreadClicked(channel: Channel) = Unit

        fun onThreadLongClicked(channel: Channel) = Unit

        fun onUrlLongClicked(url: String) = Unit

        fun onUserActivityAction(
            authorId: Long,
            channelId: Long,
            messageId: Long,
            messageActivityType: MessageActivityType,
            activity: Activity,
            application: Application,
        ) = Unit

        fun onUserMentionClicked(userId: Long, channelId: Long, guildId: Long) = Unit
    }

    /** Republishes the interaction state whenever the scroll settles or the channel changes. */
    private inner class HandlerOfScrolls : RecyclerView.OnScrollListener() {
        private var channelId: Long = 0L
        private var scrollState: Int = 0

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            super.onScrollStateChanged(recyclerView, newState)
            if (scrollState != newState || data.channelId != channelId) {
                scrollState = newState
                channelId = data.channelId
                publishInteractionState()
            }
        }
    }

    /**
     * Records the first drag since the last jump — until then the list is where the app put it, not
     * where the user put it, and edge-triggered pagination stays disabled. Also turns a tap
     * anywhere on the list into `onListClicked`, which is how the chat overlay dismisses itself.
     */
    private inner class HandlerOfTouches : View.OnTouchListener {
        private val tapGestureDetector = GestureDetector(
            recycler.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    eventHandler.onListClicked()
                    return super.onSingleTapUp(e)
                }
            },
        )

        override fun onTouch(v: View?, event: MotionEvent?): Boolean {
            val isMove = event?.action == MotionEvent.ACTION_MOVE
            if (!isTouchedSinceLastJump && isMove) {
                isTouchedSinceLastJump = true
                publishInteractionState()
            }
            return event != null && tapGestureDetector.onTouchEvent(event)
        }
    }

    /**
     * Runs once per list update, off the diff callback.
     *
     * Switching channel re-pins the list to the newest message and clears the touched flag; a new
     * oldest message is reported so the host can prefetch further back.
     */
    private inner class HandlerOfUpdates : Runnable {
        private var channelId: Long = 0L
        private var oldestMessageId: Long = 0L

        override fun run() {
            if (channelId != data.channelId) {
                val hadPreviousChannel = channelId != 0L
                channelId = data.channelId
                if (hadPreviousChannel) scrollToMessageId(0L) {}
                isTouchedSinceLastJump = false
            }
            if (oldestMessageId != data.oldestMessageId) {
                oldestMessageId = data.oldestMessageId
                eventHandler.onOldestMessageId(channelId, oldestMessageId)
            }
            publishInteractionState()
        }
    }

    /**
     * One jump attempt, rescheduled until the target row is both present and laid out.
     *
     * The row may not exist yet (the page containing it is still loading) and, even once it does,
     * `findViewByPosition` returns null until the layout pass runs — hence retrying rather than
     * scrolling once and hoping.
     */
    private class ScrollToWithHighlight(
        private val adapter: WidgetChatListAdapter,
        val messageId: Long,
        private val onCompleted: () -> Unit,
    ) : Runnable {
        private var attempts: Int = 0

        init {
            adapter.recycler.post(this)
        }

        fun cancel() {
            adapter.recycler.removeCallbacks(this)
        }

        override fun run() {
            val model = adapter.data as? WidgetChatListModel
            if (model != null && model.isLoadingMessages) {
                scheduleRetry()
                return
            }
            val index = adapter.data.list.newMessageEntryIndex()
            if (index < 0) {
                scheduleRetry()
                return
            }
            val centreOffset = (adapter.recycler.height / 2f).toInt()
            val layoutManager = adapter.layoutManager
            layoutManager?.scrollToPositionWithOffset(index, centreOffset)
            val view = layoutManager?.findViewByPosition(index)
            if (view == null) {
                scheduleRetry()
                return
            }
            // The unread divider is its own affordance; flashing it as well would be noise.
            val landedOnDivider = index < adapter.data.list.size &&
                adapter.data.list[index] is NewMessagesEntry
            if (!landedOnDivider) view.animateHighlight()
            onCompleted()
        }

        private fun scheduleRetry() {
            if (attempts >= MAX_ATTEMPTS) {
                onCompleted()
                return
            }
            attempts++
            adapter.recycler.postDelayed(this, RETRY_DELAY_MS)
        }

        private fun View.animateHighlight() {
            setBackgroundResource(R.drawable.drawable_bg_highlight)
            (background as? TransitionDrawable)?.let { transition ->
                transition.startTransition(HIGHLIGHT_FADE_IN_MS)
                postDelayed({ transition.reverseTransition(HIGHLIGHT_FADE_OUT_MS) }, HIGHLIGHT_HOLD_MS)
            }
        }

        /**
         * The row to land on.
         *
         * `0` means "wherever the unread marker is"; `1` means "the newest message". Otherwise the
         * first row at or older than [messageId] is used, since the exact message may have been
         * deleted. If the run lands next to the unread divider for the same message, the divider
         * wins so the user sees the boundary rather than the message under it.
         */
        private fun List<ChatListEntry>.newMessageEntryIndex(): Int {
            if (messageId == SCROLL_TO_NEWEST) return 0
            val isScrollToUnread = messageId == SCROLL_TO_UNREAD
            val targetId = if (isScrollToUnread) {
                adapter.data.newMessagesMarkerMessageId.takeIf { it > 0L } ?: return 0
            } else {
                messageId
            }
            if (targetId <= 0L) return -1

            var index = indexOfFirst { it is MessageEntry && it.message.id <= targetId }
            if (index < 0 && isScrollToUnread) {
                index = findBestNewMessagesPosition(lastIndex)
            }
            for (candidate in index downTo 0) {
                val entry = getOrNull(candidate)
                if (entry is NewMessagesEntry && entry.messageId == targetId) return candidate
            }
            return index
        }

        private companion object {
            private const val MAX_ATTEMPTS = 20
            private const val RETRY_DELAY_MS = 100L
            private const val HIGHLIGHT_FADE_IN_MS = 500
            private const val HIGHLIGHT_HOLD_MS = 1_000L
            private const val HIGHLIGHT_FADE_OUT_MS = 500

            /** Mirrors `StoreMessagesLoader.SCROLL_TO_LAST_UNREAD`. */
            private const val SCROLL_TO_UNREAD = 0L

            /** Mirrors `StoreMessagesLoader.SCROLL_TO_LATEST`. */
            private const val SCROLL_TO_NEWEST = 1L
        }
    }

    companion object {
        private const val NEW_MESSAGES_MIN_SCROLLBACK_COUNT = 10
        private const val NEW_MESSAGES_MAX_SCROLLBACK_COUNT = 30

        /**
         * Where to land when the unread marker is not in the loaded window.
         *
         * Fewer than ten rows behind: everything fits on screen, so land at the newest message
         * (`-1` reads as "no row", which leaves the list where it already is). Between ten and
         * thirty: land on the oldest loaded row. Beyond thirty: land thirty rows back, far enough
         * to feel like "where you left off" without scrolling through unbounded history.
         */
        internal fun findBestNewMessagesPosition(numKnownMessages: Int): Int = when {
            numKnownMessages in -1 until NEW_MESSAGES_MIN_SCROLLBACK_COUNT -> -1
            numKnownMessages < NEW_MESSAGES_MIN_SCROLLBACK_COUNT -> NEW_MESSAGES_MAX_SCROLLBACK_COUNT
            numKnownMessages >= NEW_MESSAGES_MAX_SCROLLBACK_COUNT -> NEW_MESSAGES_MAX_SCROLLBACK_COUNT
            else -> numKnownMessages
        }
    }
}
