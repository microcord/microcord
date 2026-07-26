package com.discord.widgets.chat.list

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.role.GuildRole
import com.discord.api.sticker.BaseSticker
import com.discord.app.AppFragment
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.stores.StoreChat
import com.discord.stores.StoreMessageState
import com.discord.stores.StoreMessages
import com.discord.stores.StoreMessagesLoader
import com.discord.stores.StoreReadStates
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.widgets.chat.input.WidgetChatInput
import com.discord.widgets.chat.input.emoji.WidgetEmojiPickerSheet
import com.discord.widgets.chat.list.actions.MessageActionCoordinator
import com.discord.widgets.chat.list.actions.MessageActions
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.model.ChatListRows
import com.discord.widgets.chat.list.model.WidgetChatListModel
import com.discord.widgets.stickers.StickerSheets
import com.discord.widgets.user.usersheet.WidgetUserSheet
import rx.Observable
import rx.subscriptions.CompositeSubscription

/**
 * The message list.
 *
 * The fragment owns three things: the [WidgetChatListAdapter], the store combine that produces the
 * [WidgetChatListModel] it renders from, and the [WidgetChatListAdapter.EventHandler] that turns
 * row callbacks into store calls and dialogs.
 *
 * The list is laid out **reversed** — row 0 is the newest message, pinned to the bottom — so the
 * spacer row is the first entry and pagination watches the *last* visible position.
 */
class WidgetChatList : AppFragment(R.layout.widget_chat_list), WidgetChatListActions.Host {

    private var chatAdapter: WidgetChatListAdapter? = null
    private var recycler: RecyclerView? = null
    private var subscriptions = CompositeSubscription()
    private var channelId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val list = view.findViewById<RecyclerView>(R.id.chat_list_recycler)
        recycler = list
        val adapter = MGRecyclerAdapter.configure<ChatListEntry, WidgetChatListAdapter>(
            WidgetChatListAdapter(list, this, parentFragmentManager, ChatListEventHandler()),
        )
        adapter.layoutManager?.reverseLayout = true
        list.addItemDecoration(ThreadSpineItemDecoration(requireContext(), adapter))
        list.addOnScrollListener(PaginationListener(adapter))
        chatAdapter = adapter
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val adapter = chatAdapter ?: return
        adapter.setHandlers()
        adapter.onResume()

        subscriptions = CompositeSubscription()
        val configured = stores
        subscriptions.add(
            selectedChannels()
                .switchMap { channel ->
                    channelId = channel?.id ?: 0L
                    if (channel == null || configured == null) {
                        Observable.just<WidgetChatListModel?>(null)
                    } else {
                        observeModel(channel, configured)
                    }
                }
                .subscribe { model -> post { configureUI(model) } },
        )
        configured?.loader?.observeScrollTo()?.let { commands ->
            subscriptions.add(commands.subscribe { messageId -> post { scrollTo(messageId) } })
        }
    }

    override fun onPause() {
        subscriptions.unsubscribe()
        chatAdapter?.onPause()
        chatAdapter?.disposeHandlers()
        super.onPause()
    }

    override fun onDestroyView() {
        chatAdapter?.dispose()
        chatAdapter = null
        recycler = null
        super.onDestroyView()
    }

    private fun configureUI(model: WidgetChatListModel?) {
        recycler?.visibility = if (model != null) View.VISIBLE else View.GONE
        if (model != null) chatAdapter?.data = model
    }

    fun jumpToMessage(messageId: Long) {
        if (channelId > 0 && messageId > 0) stores?.loader?.jumpToMessage(channelId, messageId)
    }

    /**
     * Hands the jump to the adapter, which retries until the target row exists, then clears the
     * pending scroll command so it is not replayed on the next subscription.
     */
    fun scrollTo(messageId: Long) {
        val adapter = chatAdapter
        if (adapter == null) {
            stores?.loader?.clearScrollTo()
            return
        }
        adapter.scrollToMessageId(messageId) { stores?.loader?.clearScrollTo() }
    }

    // region model

    private fun observeModel(channel: Channel, stores: Stores): Observable<WidgetChatListModel?> {
        val history = Observable.combineLatest(
            stores.messages.observeMessagesForChannel(channel.id),
            stores.loader.getMessagesLoadedState(channel.id),
            stores.messages.observeIsDetached(channel.id),
            StoreStream.getMessageState().getMessageState(),
            StoreStream.getMessagesMostRecent().observeRecentMessageIds(channel.id),
            ::History,
        ).doOnNext { markReadIfCaughtUp(channel, it) }
        val context = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(channel.guildId),
            StoreStream.getGuilds().observeComputed(channel.guildId),
            StoreStream.getGuilds().observeRoles(channel.guildId),
            StoreStream.getChannels().observeNames(),
            StoreStream.getUsers().observeMeId(),
            ::GuildContext,
        )
        val preferences = Observable.combineLatest(
            unreadMarkers(channel.id),
            StoreStream.getPermissions().observePermissionsForChannel(channel.id),
            StoreStream.getUserSettings().observeIsAnimatedEmojisEnabled(),
            StoreStream.getUserSettings().observeIsAutoPlayGifsEnabled(),
            StoreStream.getUserSettings().observeIsRenderEmbedsEnabled(),
            ::Preferences,
        )
        // The result type is pinned explicitly: `rx.Observable` is a Java type and therefore
        // invariant, so an inferred `Observable<WidgetChatListModel>` would not satisfy the
        // nullable return type the "no channel selected" branch needs.
        return Observable.combineLatest<History, GuildContext, Preferences, WidgetChatListModel?>(
            history,
            context,
            preferences,
        ) { messages, guild, prefs -> buildModel(channel, messages, guild, prefs) }
    }

    /**
     * Acks the channel once its history is on screen.
     *
     * The reference acks from `StoreMessageAck`, driven by the chat list's scroll position. That
     * store is not reachable from `StoreStream` yet, so the channel is acked as soon as it is
     * attached and its first page has landed — which is what this screen did before the adapter
     * migration.
     */
    private fun markReadIfCaughtUp(channel: Channel, history: History) {
        if (history.detached || !history.loaded.isInitialMessagesLoaded) return
        val readStates = StoreStream.getReadStates()
        if (channel.id in readStates.getUnreadChannelIdsSnapshot()) readStates.markAsRead(channel.id)
    }

    private fun buildModel(
        channel: Channel,
        history: History,
        context: GuildContext,
        preferences: Preferences,
    ): WidgetChatListModel {
        val marker = preferences.unread.marker
        val newMessagesMarkerMessageId =
            if (marker.channelId == channel.id) marker.messageId ?: 0L else 0L
        val rows = ChatListRows.build(
            ChatListRows.State(
                channel = channel,
                parentChannel = StoreStream.getChannels().getChannel(channel.parentId),
                messages = history.messages,
                loaded = history.loaded,
                detached = history.detached,
                unreadMessageId = newMessagesMarkerMessageId.takeIf { it > 0L },
                permissions = preferences.permissions,
                guild = context.guild,
                guildMembers = context.guildMembers,
                guildRoles = context.guildRoles,
                messageStates = history.messageStates,
                animateEmojis = preferences.animateEmojis,
                autoPlayGifs = preferences.autoPlayGifs,
                renderEmbeds = preferences.renderEmbeds,
            ),
        )
        return WidgetChatListModel(
            userId = context.meId,
            channelId = channel.id,
            guild = context.guild,
            guildId = channel.guildId,
            channelNames = context.channelNames,
            oldestMessageId = history.messages.minOfOrNull(Message::id) ?: 0L,
            list = rows,
            myRoleIds = context.guildMembers[context.meId]?.getRoles()?.toSet().orEmpty(),
            newMessagesMarkerMessageId = newMessagesMarkerMessageId,
            newestKnownMessageId = history.newestKnownMessageId,
            isLoadingMessages = history.loaded.isLoadingMessages,
        )
    }

    /** The channel's own history, plus everything that describes how much of it is loaded. */
    private class History(
        val messages: List<Message>,
        val loaded: StoreMessagesLoader.ChannelLoadedState,
        val detached: Boolean,
        val messageStates: Map<Long, StoreMessageState.State>,
        val newestKnownMessageId: Long,
    )

    /** The guild the channel belongs to, and who the viewer is inside it. */
    private class GuildContext(
        val guild: Guild?,
        val guildMembers: Map<Long, GuildMember>,
        val guildRoles: Map<Long, GuildRole>,
        val channelNames: Map<Long, String>,
        val meId: Long,
    )

    /** The read cursor and the four viewer settings that change how rows render. */
    private class Preferences(
        val unread: StoreReadStates.Unread,
        val permissions: Long?,
        val animateEmojis: Boolean,
        val autoPlayGifs: Boolean,
        val renderEmbeds: Boolean,
    )

    // endregion

    // region interaction

    /**
     * Pages older history in as the list approaches its oldest loaded row.
     *
     * The reference does this from `StoreChat.InteractionState`, which `StoreMessagesLoader`
     * subscribes to in `init`. `StoreStream` never constructs a `StoreChat`, so
     * `StoreMessagesLoader.init` is never called and the interaction state the adapter publishes
     * goes nowhere — see [ChatListEventHandler.onInteractionStateUpdated]. Until that store is
     * wired, the recycler drives pagination directly.
     */
    private inner class PaginationListener(
        private val adapter: WidgetChatListAdapter,
    ) : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
            if (manager.findLastVisibleItemPosition() < adapter.itemCount - PREFETCH_ROWS) return
            val oldest = adapter.data.oldestMessageId.takeIf { it > 0L } ?: return
            stores?.loader?.loadBefore(adapter.data.channelId, oldest)
        }
    }

    /** Turns row callbacks into store calls, sheets and dialogs. */
    private inner class ChatListEventHandler : WidgetChatListAdapter.EventHandler {

        override fun onMessageClicked(message: Message, isThreadStarterMessage: Boolean) =
            onMessageClick(message)

        override fun onMessageLongClicked(
            message: Message,
            formattedMessage: CharSequence,
            isThreadStarterMessage: Boolean,
        ) {
            handleMessageLongClick(message)
        }

        override fun onDismissLocalMessageClicked(message: Message) {
            message.nonce?.let { StoreStream.cancelMessageSend(it) }
        }

        override fun onReactionClicked(
            guildId: Long,
            myUserId: Long,
            channelId: Long,
            messageId: Long,
            reaction: MessageReaction,
            canAddReactions: Boolean,
        ) {
            if (!canAddReactions) {
                Toast.makeText(
                    requireContext(),
                    R.string.archived_thread_reactions_disabled_toast,
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            val message = StoreStream.getMessages().getMessage(channelId, messageId) ?: return
            actionCoordinator?.toggleReaction(message, reaction.emoji)
        }

        override fun onReactionLongClicked(
            guildId: Long,
            channelId: Long,
            messageId: Long,
            reaction: MessageReaction,
        ) {
            val message = StoreStream.getMessages().getMessage(channelId, messageId) ?: return
            onReactionLongClick(message, reaction)
        }

        override fun onQuickAddReactionClicked(
            guildId: Long,
            myUserId: Long,
            channelId: Long,
            messageId: Long,
        ) {
            val message = StoreStream.getMessages().getMessage(channelId, messageId) ?: return
            requestMessageReaction(message)
        }

        override fun onMessageAuthorAvatarClicked(message: Message, guildId: Long) {
            message.author?.id?.let { WidgetUserSheet.show(it, parentFragmentManager) }
        }

        override fun onMessageAuthorNameClicked(message: Message, guildId: Long) {
            message.author?.id?.let { WidgetUserSheet.show(it, parentFragmentManager) }
        }

        override fun onUserMentionClicked(userId: Long, channelId: Long, guildId: Long) {
            WidgetUserSheet.show(userId, parentFragmentManager)
        }

        override fun onMessageAuthorLongClicked(message: Message, guildId: Long?) {
            message.author?.id?.let { WidgetChatInput.requestMention(it) }
        }

        /**
         * A message only carries a partial sticker, so [StickerSheets] resolves the full one before
         * choosing between the standard, guild and unknown popouts.
         */
        override fun onStickerClicked(message: Message, sticker: BaseSticker) {
            StickerSheets.show(
                parentFragmentManager,
                this@WidgetChatList,
                sticker,
                message.channelId,
            )
        }

        /**
         * Deliberately inert. `StoreChat` is never constructed by `StoreStream`, so there is
         * nothing to publish the scroll position to; [PaginationListener] covers the one behaviour
         * that depended on it. Wire this to `StoreStream.getChat().setInteractionState(...)` the
         * moment that store is owned.
         */
        override fun onInteractionStateUpdated(interactionState: StoreChat.InteractionState) = Unit
    }

    // endregion

    private fun post(action: () -> Unit) {
        recycler?.post { if (view != null) action() }
    }

    private fun handleMessageLongClick(message: Message): Boolean {
        if (onMessageLongClick(message)) return true
        val channel = StoreStream.getChannels().getChannel(message.channelId) ?: return false
        val currentUserId = StoreStream.getUsers().getMe().id
        WidgetChatListActions.show(
            childFragmentManager,
            message.channelId,
            message.id,
            MessageActions.Input(
                authorId = message.author?.id,
                currentUserId = currentUserId,
                messageType = message.type,
                isLocal = message.isLocal(),
                canResend = message.canResend(),
                hasContent = !message.content.isNullOrEmpty(),
                permissions = StoreStream.getPermissions().getPermissionsByChannel()[message.channelId],
                pinned = message.pinned == true,
                channelType = channel.type,
            ),
        )
        return true
    }

    override fun messageActionCoordinator(): MessageActionCoordinator = requireNotNull(actionCoordinator)

    override fun messageForAction(channelId: Long, messageId: Long): Message? =
        StoreStream.getMessages().getMessage(channelId, messageId)

    override fun channelForAction(channelId: Long): Channel? =
        StoreStream.getChannels().getChannel(channelId)

    override fun requestMessageEdit(message: Message, coordinator: MessageActionCoordinator) {
        StoreStream.getChatInputEditing().start(message)
    }

    override fun requestMessageReaction(message: Message, coordinator: MessageActionCoordinator) {
        requestMessageReaction(message)
    }

    private fun requestMessageReaction(message: Message) =
        WidgetEmojiPickerSheet.show(childFragmentManager) { emoji ->
            actionCoordinator?.toggleReaction(message, emoji)
        }

    data class Stores(val messages: StoreMessages, val loader: StoreMessagesLoader)

    companion object {
        /** How close to the oldest loaded row the list must get before more history is fetched. */
        private const val PREFETCH_ROWS = 3

        @Volatile var stores: Stores? = null
        @Volatile var actionCoordinator: MessageActionCoordinator? = null

        var selectedChannels: () -> Observable<Channel?> = {
            StoreStream.getChannelsSelected().observeSelectedChannel()
        }
        var unreadMarkers: (Long) -> Observable<StoreReadStates.Unread> = { channelId ->
            StoreStream.getReadStates().getUnreadMarker(channelId)
        }
        var onMessageClick: (Message) -> Unit = {}
        var onMessageLongClick: (Message) -> Boolean = { false }
        var onReactionLongClick: (Message, MessageReaction) -> Boolean = { _, _ -> false }

        fun installStores(messages: StoreMessages, loader: StoreMessagesLoader) {
            stores = Stores(messages, loader)
        }
    }
}
