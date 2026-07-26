package com.discord.widgets.chat.list

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.models.message.Message
import com.discord.stores.StoreMessages
import com.discord.stores.StoreMessagesLoader
import com.discord.stores.StoreReadStates
import com.discord.stores.StoreStream
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.model.ChatListRows
import com.discord.widgets.chat.list.actions.MessageActionCoordinator
import com.discord.widgets.chat.list.actions.MessageActions
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.api.message.reaction.MessageReaction
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.chat.input.WidgetChatInput
import com.discord.widgets.chat.input.emoji.WidgetEmojiPickerSheet
import android.widget.Toast
import rx.Observable
import rx.subscriptions.CompositeSubscription

class WidgetChatList : AppFragment(R.layout.widget_chat_list), WidgetChatListActions.Host {
    private lateinit var chatAdapter: WidgetChatListAdapter
    private var subscriptions = CompositeSubscription()
    private var recycler: RecyclerView? = null
    private var channelId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
        if (!::chatAdapter.isInitialized) chatAdapter = WidgetChatListAdapter(
            { onMessageClick(it) },
            { handleMessageLongClick(it) },
            { StoreStream.cancelMessageSend(it) },
            { message, emoji, canAdd ->
                if (canAdd) actionCoordinator?.toggleReaction(message, emoji)
                else Toast.makeText(requireContext(), R.string.archived_thread_reactions_disabled_toast, Toast.LENGTH_SHORT).show()
            },
            { message, reaction -> onReactionLongClick(message, reaction) },
            ::requestMessageReaction,
            { userId -> WidgetUserSheet.show(userId, parentFragmentManager) },
            WidgetChatInput::requestMention,
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById<RecyclerView>(R.id.chat_list_recycler).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, true)
            adapter = chatAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val manager = recyclerView.layoutManager as LinearLayoutManager
                    if (manager.findLastVisibleItemPosition() >= chatAdapter.itemCount - 3) {
                        val oldest = chatAdapter.oldestMessageId ?: return
                        stores?.loader?.loadBefore(channelId, oldest)
                    }
                }
            })
        }
    }

    override fun onStart() {
        super.onStart()
        subscriptions = CompositeSubscription()
        val configured = stores
        subscriptions.add(selectedChannels().switchMap { channel ->
            channelId = channel?.id ?: 0L
            if (channel == null || configured == null) {
                Observable.just<ChatListRows.State?>(null)
            } else {
                observeState(channel, configured)
            }
        }.subscribe { state -> post {
            if (state != null && !state.detached && state.loaded.isInitialMessagesLoaded &&
                state.channel.id in StoreStream.getReadStates().getUnreadChannelIdsSnapshot()) {
                StoreStream.getReadStates().markAsRead(state.channel.id)
            }
            chatAdapter.submitList(state?.let(ChatListRows::build).orEmpty())
        } })
        configured?.loader?.observeScrollTo()?.let { commands ->
            subscriptions.add(commands.subscribe { messageId -> post { scrollTo(messageId) } })
        }
    }

    override fun onStop() {
        subscriptions.unsubscribe()
        super.onStop()
    }

    override fun onDestroyView() {
        recycler?.adapter = null
        recycler = null
        super.onDestroyView()
    }

    fun jumpToMessage(messageId: Long) {
        if (channelId > 0 && messageId > 0) stores?.loader?.jumpToMessage(channelId, messageId)
    }

    fun scrollTo(messageId: Long) {
        val list = recycler ?: return
        val position = when (messageId) {
            StoreMessagesLoader.SCROLL_TO_LATEST -> 0
            StoreMessagesLoader.SCROLL_TO_LAST_UNREAD -> chatAdapter.currentList
                .indexOfFirst { it is ChatListEntry.UnreadDivider }
            else -> chatAdapter.positionForMessage(messageId)
        }
        if (position >= 0) (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 0)
    }

    private fun observeState(channel: Channel, stores: Stores): Observable<ChatListRows.State?> =
        Observable.combineLatest(
            Observable.combineLatest(
                stores.messages.observeMessagesForChannel(channel.id),
                stores.loader.getMessagesLoadedState(channel.id),
                StoreStream.getMessageUploads().observeAll(),
            ) { messages, loaded, uploads -> Triple(messages, loaded, uploads) },
            Observable.combineLatest(
                stores.messages.observeIsDetached(channel.id),
                unreadMarkers(channel.id),
            ) { detached, unread -> detached to unread },
        ) { history, projection ->
            ChatListRows.State(
                channel,
                history.first,
                history.second,
                projection.first,
                projection.second.marker.messageId.takeIf { projection.second.marker.channelId == channel.id },
                history.third,
                StoreStream.getPermissions().getPermissionsByChannel()[channel.id],
            )
        }

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
    override fun channelForAction(channelId: Long): Channel? = StoreStream.getChannels().getChannel(channelId)
    override fun requestMessageEdit(message: Message, coordinator: MessageActionCoordinator) {
        StoreStream.getChatInputEditing().start(message)
    }
    override fun requestMessageReaction(message: Message, coordinator: MessageActionCoordinator) {
        requestMessageReaction(message)
    }

    private fun requestMessageReaction(message: Message) = WidgetEmojiPickerSheet.show(childFragmentManager) { emoji ->
        actionCoordinator?.toggleReaction(message, emoji)
    }

    data class Stores(val messages: StoreMessages, val loader: StoreMessagesLoader)

    companion object {
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
