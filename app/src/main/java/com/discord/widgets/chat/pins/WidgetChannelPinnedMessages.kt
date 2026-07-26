package com.discord.widgets.chat.pins

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isPrivate
import com.discord.app.AppFragment
import com.discord.models.guild.Guild
import com.discord.models.message.Message
import com.discord.stores.StorePinnedMessages
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.widgets.chat.list.ThreadSpineItemDecoration
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.DividerEntry
import com.discord.widgets.chat.list.entries.EmptyPinsEntry
import com.discord.widgets.chat.list.entries.LoadingEntry
import com.discord.widgets.chat.list.model.ChatListRows
import rx.Subscription

/**
 * The pinned-messages surface.
 *
 * It renders through the same [WidgetChatListAdapter] as the live chat, but with a read-only
 * [Model]: `isSpoilerClickAllowed` is false, so tapping a spoiler in a pinned message does not
 * reveal it. Mention highlighting is left on, matching the reference — only the recent-mentions
 * screen turns it off.
 */
class WidgetChannelPinnedMessages : AppFragment(R.layout.widget_channel_pinned_messages) {

    interface Host {
        fun pinnedMessagesStore(): StorePinnedMessages
        fun channelForPins(channelId: Long): Channel?
        fun jumpToPinnedMessage(channelId: Long, messageId: Long)
        fun onPinnedMessageLongPress(message: Message): Boolean = false
    }

    private var subscription: Subscription? = null
    private var adapter: WidgetChatListAdapter? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val host = host()
        val channelId = requireArguments().getLong(ARG_CHANNEL_ID)
        val channel = host.channelForPins(channelId)

        val recycler = view.findViewById<RecyclerView>(R.id.channel_pinned_messages_recycler)
        val chatAdapter = MGRecyclerAdapter.configure<ChatListEntry, WidgetChatListAdapter>(
            WidgetChatListAdapter(
                recycler,
                this,
                parentFragmentManager,
                PinnedMessagesEventHandler(host, channelId),
            ),
        )
        recycler.addItemDecoration(ThreadSpineItemDecoration(requireContext(), chatAdapter))
        adapter = chatAdapter

        view.findDescendant(Toolbar::class.java)?.apply {
            setTitle(R.string.pinned_messages)
            subtitle = channel?.name
            setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        }
        setOnBackPressed(handler = {
            val handled = parentFragmentManager.backStackEntryCount > 0
            if (handled) parentFragmentManager.popBackStack()
            handled
        })

        val context = requireContext()
        subscription = host.pinnedMessagesStore().observeForChannel(channelId).subscribe(
            { messages -> view.post { chatAdapter.data = model(context, channelId, channel, messages) } },
            { view.post { chatAdapter.data = model(context, channelId, channel, emptyList()) } },
        )
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        adapter?.setHandlers()
    }

    override fun onPause() {
        adapter?.disposeHandlers()
        super.onPause()
    }

    override fun onDestroyView() {
        subscription?.unsubscribe()
        subscription = null
        adapter?.dispose()
        adapter = null
        super.onDestroyView()
    }

    private fun host(): Host = (parentFragment as? Host) ?: (activity as? Host)
        ?: error("WidgetChannelPinnedMessages host must implement Host")

    private fun <T : View> View.findDescendant(type: Class<T>): T? {
        if (type.isInstance(this)) return type.cast(this)
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) getChildAt(index).findDescendant(type)?.let { return it }
        return null
    }

    /** Forwards the two interactions this surface supports; everything else keeps its no-op default. */
    private class PinnedMessagesEventHandler(
        private val host: Host,
        private val channelId: Long,
    ) : WidgetChatListAdapter.EventHandler {

        override fun onMessageClicked(message: Message, isThreadStarterMessage: Boolean) =
            host.jumpToPinnedMessage(channelId, message.id)

        override fun onMessageLongClicked(
            message: Message,
            formattedMessage: CharSequence,
            isThreadStarterMessage: Boolean,
        ) {
            host.onPinnedMessageLongPress(message)
        }
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
        private const val ARG_CHANNEL_ID = "channel_id"

        fun newInstance(channelId: Long) = WidgetChannelPinnedMessages().apply {
            arguments = Bundle().apply { putLong(ARG_CHANNEL_ID, channelId) }
        }

        /**
         * The rows for a pinned-messages list: the loading indicator while the fetch is in flight,
         * the empty state when the channel has no pins, otherwise every pinned message separated by
         * a hairline.
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
            channelId: Long,
            channel: Channel?,
            messages: List<Message>?,
        ): WidgetChatListAdapter.Data {
            val resolved = channel ?: StoreStream.getChannels().getChannel(channelId)
            val emptyText = context.getString(
                if (resolved?.isPrivate() == true) R.string.no_pins_in_dm else R.string.no_pins_in_channel,
            )
            val rows = rows(emptyText, resolved?.let(::pinnedRowState), messages)
            if (resolved == null) return UnresolvedChannel(channelId, rows)
            val meId = StoreStream.getUsers().getMe().id
            return Model(
                channel = resolved,
                guild = StoreStream.getGuilds().getGuild(resolved.guildId),
                userId = meId,
                channelNames = StoreStream.getChannels().getChannelNames(),
                list = rows,
                myRoleIds = StoreStream.getGuilds().getMembers()[resolved.guildId]?.get(meId)
                    ?.getRoles()?.toSet().orEmpty(),
            )
        }
    }

    /** Fallback for the window between opening the screen and the channel resolving. */
    private class UnresolvedChannel(
        override val channelId: Long,
        override val list: List<ChatListEntry>,
    ) : WidgetChatListAdapter.Data {
        override val channelNames: Map<Long, String> = emptyMap()
        override val guild: Guild? = null
        override val guildId: Long = 0L
        override val myRoleIds: Set<Long> = emptySet()
        override val newMessagesMarkerMessageId: Long = 0L
        override val oldestMessageId: Long = 0L
        override val userId: Long = 0L
        override val isSpoilerClickAllowed: Boolean = false
    }
}
