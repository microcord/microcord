package com.discord.widgets.chat.pins

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isPrivate
import com.discord.app.AppActivity
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppTransitionActivity
import com.discord.models.guild.Guild
import com.discord.models.message.Message
import com.discord.stores.StorePinnedMessages
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.widgets.chat.list.ThreadSpineItemDecoration
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.actions.MessageActionCoordinator
import com.discord.widgets.chat.list.actions.MessageActions
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.DividerEntry
import com.discord.widgets.chat.list.entries.EmptyPinsEntry
import com.discord.widgets.chat.list.entries.LoadingEntry
import com.discord.widgets.chat.list.model.ChatListRows
import com.discord.widgets.chat.input.emoji.WidgetEmojiPickerSheet
import rx.Subscription

/**
 * The pinned-messages surface.
 *
 * It renders through the same [WidgetChatListAdapter] as the live chat, but with a read-only
 * [Model]: `isSpoilerClickAllowed` is false, so tapping a spoiler in a pinned message does not
 * reveal it. Mention highlighting is left on, matching the reference — only the recent-mentions
 * screen turns it off.
 */
class WidgetChannelPinnedMessages : AppFragment(R.layout.widget_channel_pinned_messages), WidgetChatListActions.Host {

    private var subscription: Subscription? = null
    private var adapter: WidgetChatListAdapter? = null
    private var pinnedMessages: Map<Long, Message> = emptyMap()
    private var channel: Channel? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val channelId = mostRecentIntent.getLongExtra(INTENT_EXTRA_CHANNEL_ID, 0L)
        val channel = StoreStream.getChannels().getChannel(channelId)
        if (channel == null) {
            requireActivity().finish()
            return
        }
        this.channel = channel

        val recycler = view.findViewById<RecyclerView>(R.id.channel_pinned_messages_recycler)
        val chatAdapter = MGRecyclerAdapter.configure<ChatListEntry, WidgetChatListAdapter>(
            WidgetChatListAdapter(
                recycler,
                this,
                parentFragmentManager,
                PinnedMessagesEventHandler(),
                stickToBottom = false,
            ),
        )
        recycler.addItemDecoration(ThreadSpineItemDecoration(requireContext(), chatAdapter))
        recycler.addOnScrollListener(pinPagingListener(channel.id))
        adapter = chatAdapter

        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.pinned_messages)
        setActionBarSubtitle(channel.getDisplayNameOrDefault(requireContext(), includePrefix = false))
    }

    /**
     * Pins arrive one page at a time, so the next page is asked for as the list nears its end.
     * `StorePinnedMessages.loadMore` is a no-op while a request is in flight or once the server has
     * said this is the last page, so there is no need to debounce here.
     */
    private fun pinPagingListener(channelId: Long) = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy <= 0) return
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
            val remaining = layoutManager.itemCount - 1 - layoutManager.findLastVisibleItemPosition()
            if (remaining <= PAGE_PREFETCH_DISTANCE) StoreStream.getPinnedMessages().loadMore(channelId)
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val channel = channel ?: return
        val boundView = view ?: return
        val channelId = channel.id
        StoreStream.getMessageAck().ackPins(channelId)
        subscription?.unsubscribe()
        subscription = observeModel(requireContext(), channelId, channel, StoreStream.getPinnedMessages()).subscribe { (messages, data) ->
            boundView.post {
                if (view !== boundView) return@post
                pinnedMessages = messages.associateBy(Message::id)
                adapter?.data = data
            }
        }
        adapter?.setHandlers()
    }

    override fun onPause() {
        super.onPause()
        subscription?.unsubscribe()
        subscription = null
        adapter?.disposeHandlers()
    }

    override fun onDestroyView() {
        subscription?.unsubscribe()
        subscription = null
        adapter?.dispose()
        adapter = null
        pinnedMessages = emptyMap()
        channel = null
        super.onDestroyView()
    }

    private inner class PinnedMessagesEventHandler : WidgetChatListAdapter.EventHandler {

        override fun onMessageClicked(message: Message, isThreadStarterMessage: Boolean) {
            StoreStream.getMessagesLoader().jumpToMessage(message.channelId, message.id)
            returnToMain()
        }

        override fun onMessageLongClicked(
            message: Message,
            formattedMessage: CharSequence,
            isThreadStarterMessage: Boolean,
        ) {
            WidgetChatListActions.showForPin(
                childFragmentManager,
                message.channelId,
                message.id,
                actionInput(message, message.channelId),
            )
        }

        override fun onStickerClicked(message: Message, sticker: com.discord.api.sticker.BaseSticker) =
            onMessageClicked(message, false)

        override fun onThreadClicked(channel: Channel) {
            StoreStream.selectChannel(channel.guildId, channel.id)
            returnToMain()
        }

        override fun onUrlLongClicked(url: String) =
            com.discord.widgets.chat.WidgetUrlActions.launch(parentFragmentManager, url)
    }

    private fun returnToMain() {
        StoreStream.getTabsNavigation().selectHomeTab(StoreNavigation.PanelAction.CLOSE, true)
        startActivity(
            Intent(requireContext(), AppActivity.Main::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        activity?.finish()
    }

    override fun messageActionCoordinator(): MessageActionCoordinator =
        requireNotNull(WidgetChatList.actionCoordinator) { "Chat message actions are not initialized" }

    override fun messageForAction(channelId: Long, messageId: Long): Message? = pinnedMessages[messageId]

    override fun channelForAction(channelId: Long): Channel? =
        StoreStream.getChannels().getChannel(channelId)

    override fun requestMessageEdit(message: Message, coordinator: MessageActionCoordinator) = Unit

    override fun requestMessageReaction(message: Message, coordinator: MessageActionCoordinator) {
        WidgetEmojiPickerSheet.show(childFragmentManager) { emoji -> coordinator.toggleReaction(message, emoji) }
    }

    private fun actionInput(message: Message, channelId: Long): MessageActions.Input {
        val channel = channelForAction(channelId)
        return MessageActions.Input(
            authorId = message.author?.id,
            currentUserId = StoreStream.getUsers().getMe().id,
            messageType = message.type,
            isLocal = message.isLocal(),
            canResend = message.canResend(),
            hasContent = !message.content.isNullOrEmpty(),
            permissions = StoreStream.getPermissions().getPermissionsByChannel()[channelId],
            pinned = message.pinned == true,
            channelType = channel?.type ?: Channel.GUILD_TEXT,
        )
    }

    /**
     * Read-only [WidgetChatListAdapter.Data]. `oldestMessageId` and `newMessagesMarkerMessageId`
     * stay 0: this list never paginates and has no unread cursor.
     */
    private data class Model(
        val channel: Channel,
        override val guild: Guild?,
        override val userId: Long,
        override val channelNames: Map<Long, String>,
        override val list: List<ChatListEntry>,
        override val myRoleIds: Set<Long>,
        override val channelId: Long = channel.id,
        override val guildId: Long = channel.guildId,
        override val oldestMessageId: Long = 0L,
        override val newMessagesMarkerMessageId: Long = 0L,
        override val isSpoilerClickAllowed: Boolean = false,
    ) : WidgetChatListAdapter.Data

    companion object {
        internal const val INTENT_EXTRA_CHANNEL_ID = "INTENT_EXTRA_CHANNEL_ID"

        /** Rows left below the fold before the next page is requested. */
        private const val PAGE_PREFETCH_DISTANCE = 5
        internal val TRANSITION = AppTransitionActivity.Transition.TYPE_SLIDE_HORIZONTAL

        fun show(context: Context, channelId: Long) {
            AppScreenRouter.launch(
                context,
                WidgetChannelPinnedMessages::class.java,
                Intent().putExtra(INTENT_EXTRA_CHANNEL_ID, channelId),
                TRANSITION,
            )
        }

        /**
         * The reference keeps a dormant null/loading branch, but a valid channel receives an empty
         * list until REST completes. Loaded messages are separated by a hairline.
         */
        internal fun rows(
            emptyText: String,
            state: ChatListRows.State?,
            messages: List<Message>?,
        ): List<ChatListEntry> = when {
            messages == null || state == null -> listOf(LoadingEntry())
            messages.isEmpty() -> listOf(EmptyPinsEntry(emptyText))
            else -> messages.flatMapIndexed { index, message ->
                val rows = ChatListRows.messageRows(state, message)
                if (index < messages.lastIndex) rows + DividerEntry() else rows
            }
        }

        private fun pinnedRowState(channel: Channel) = ChatListRows.State(
            channel = channel,
            guild = StoreStream.getGuilds().getGuild(channel.guildId),
            guildMembers = StoreStream.getGuilds().getMembers()[channel.guildId].orEmpty(),
            guildRoles = StoreStream.getGuilds().getRoles()[channel.guildId].orEmpty(),
            permissions = StoreStream.getPermissions().getPermissionsByChannel()[channel.id],
            animateEmojis = StoreStream.getUserSettings().getIsAnimatedEmojisEnabled(),
            autoPlayGifs = StoreStream.getUserSettings().getIsAutoPlayGifsEnabled(),
            renderEmbeds = StoreStream.getUserSettings().getIsRenderEmbedsEnabled(),
        )

        private fun model(
            context: Context,
            channel: Channel,
            messages: List<Message>?,
        ): WidgetChatListAdapter.Data {
            val emptyText = context.getString(
                if (channel.isPrivate()) R.string.no_pins_in_dm else R.string.no_pins_in_channel,
            )
            val rows = rows(emptyText, pinnedRowState(channel), messages)
            val meId = StoreStream.getUsers().getMe().id
            return Model(
                channel = channel,
                guild = StoreStream.getGuilds().getGuild(channel.guildId),
                userId = meId,
                channelNames = StoreStream.getChannels().getChannelNames(),
                list = rows,
                myRoleIds = StoreStream.getGuilds().getMembers()[channel.guildId]?.get(meId)
                    ?.getRoles()?.toSet().orEmpty(),
            )
        }

        private fun observeModel(
            context: Context,
            channelId: Long,
            channel: Channel,
            store: StorePinnedMessages,
        ): rx.Observable<Pair<List<Message>, WidgetChatListAdapter.Data>> {
            val updates = rx.Observable.merge(
                listOf(
                    StoreStream.getUsers().observeMeId().map { Unit },
                    StoreStream.getGuilds().observeRoles(channel.guildId).map { Unit },
                    StoreStream.getGuilds().observeComputed(channel.guildId).map { Unit },
                    StoreStream.getGuilds().observeGuild(channel.guildId).map { Unit },
                    StoreStream.getChannels().observeNames().map { Unit },
                    StoreStream.getPermissions().observePermissionsForChannel(channelId).map { Unit },
                    StoreStream.getUserSettings().observeIsAnimatedEmojisEnabled(false).map { Unit },
                    StoreStream.getUserSettings().observeIsAutoPlayGifsEnabled(false).map { Unit },
                    StoreStream.getUserSettings().observeIsRenderEmbedsEnabled().map { Unit },
                ),
            )
            return rx.Observable.combineLatest(store.observeForChannel(channelId), updates) { messages, _ ->
                messages to model(context, channel, messages)
            }.distinctUntilChanged()
        }
    }

}
