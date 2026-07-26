package com.discord.widgets.user

import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.models.guild.Guild
import com.discord.models.message.Message
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.widgets.chat.list.ThreadSpineItemDecoration
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.LoadingEntry
import com.discord.widgets.chat.list.entries.MentionFooterEntry
import com.discord.widgets.chat.list.entries.MessageHeaderEntry
import com.discord.widgets.chat.list.model.ChatListRows
import rx.Subscription
import rx.schedulers.Schedulers

/**
 * Recent mentions.
 *
 * Every result is a message from a different channel, so each one is preceded by a
 * [MessageHeaderEntry] naming the guild and channel it came from. The list is not reversed — unlike
 * the chat list, results read newest-first from the top — and it never paginates upwards, so the
 * footer marking the end of the results is a row rather than a scroll state.
 */
class WidgetUserMentions : AppFragment(R.layout.widget_user_mentions) {

    private var adapter: WidgetChatListAdapter? = null
    private val messages = ArrayList<Message>()
    private var request: Subscription? = null
    private var loading = false
    private var allLoaded = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<Toolbar>(R.id.action_bar_toolbar).setTitle(R.string.recent_mentions)
        val recycler = view.findViewById<RecyclerView>(R.id.user_mentions_list)
        val mentionsAdapter = MGRecyclerAdapter.configure<ChatListEntry, WidgetChatListAdapter>(
            WidgetChatListAdapter(recycler, this, parentFragmentManager, MentionsEventHandler()),
        ).apply { mentionMeMessageLevelHighlighting = false }
        recycler.addItemDecoration(ThreadSpineItemDecoration(requireContext(), mentionsAdapter))
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (dy > 0 && manager.findLastVisibleItemPosition() >= mentionsAdapter.itemCount - PREFETCH_ROWS) {
                    load()
                }
            }
        })
        adapter = mentionsAdapter
        load()
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
        request?.unsubscribe()
        request = null
        adapter?.dispose()
        adapter = null
        super.onDestroyView()
    }

    private fun load() {
        if (loading || allLoaded) return
        loading = true
        render()
        request = StoreStream.getMentions(messages.minOfOrNull(Message::id))
            .subscribeOn(Schedulers.io())
            .subscribe(
                { page ->
                    view?.post {
                        val converted = page.map(::Message)
                        messages.addAll(converted.filter { incoming -> messages.none { it.id == incoming.id } })
                        messages.sortByDescending(Message::id)
                        allLoaded = page.size < PAGE_SIZE
                        loading = false
                        render()
                    }
                },
                { view?.post { loading = false; render() } },
            )
    }

    private fun render() {
        val rows = ArrayList<ChatListEntry>()
        messages.forEach { message ->
            val channel = StoreStream.getChannels().getChannel(message.channelId) ?: return@forEach
            val guild = StoreStream.getGuilds().getGuild(channel.guildId)
            val state = ChatListRows.State(
                channel = channel,
                guild = guild,
                guildMembers = StoreStream.getGuilds().getMembers()[channel.guildId].orEmpty(),
                guildRoles = StoreStream.getGuilds().getRoles()[channel.guildId].orEmpty(),
                permissions = StoreStream.getPermissions().getPermissionsByChannel()[channel.id],
                animateEmojis = StoreStream.getUserSettings().getIsAnimatedEmojisEnabled(),
                autoPlayGifs = StoreStream.getUserSettings().getIsAutoPlayGifsEnabled(),
                renderEmbeds = StoreStream.getUserSettings().getIsRenderEmbedsEnabled(),
            )
            rows += ChatListRows.contextMessageRows(state, message)
        }
        if (loading) rows += LoadingEntry() else if (allLoaded) rows += MentionFooterEntry()
        adapter?.data = Model(
            userId = StoreStream.getUsers().getMe().id,
            guildId = StoreStream.getGuildSelected().getSelectedGuildId(),
            guild = StoreStream.getGuilds().getGuild(StoreStream.getGuildSelected().getSelectedGuildId()),
            channelNames = StoreStream.getChannels().getChannelNames(),
            list = rows,
        )
    }

    private fun openMessage(message: Message) {
        val channel = StoreStream.getChannels().getChannel(message.channelId) ?: return
        StoreStream.selectChannel(channel.guildId, channel.id)
        StoreStream.getMessagesLoader().jumpToMessage(channel.id, message.id)
        StoreStream.getTabsNavigation().selectHomeTab(StoreNavigation.PanelAction.CLOSE, true)
    }

    /** Tapping a result leaves this screen for the message in its own channel. */
    private inner class MentionsEventHandler : WidgetChatListAdapter.EventHandler {
        override fun onMessageClicked(message: Message, isThreadStarterMessage: Boolean) =
            openMessage(message)
    }

    /**
     * Read-only [WidgetChatListAdapter.Data] spanning many channels at once.
     *
     * `channelId`, `oldestMessageId` and `newMessagesMarkerMessageId` are `-1` rather than `0`,
     * matching the reference: this list belongs to no single channel, and `0` would read as
     * "channel 0" to the adapter's channel-change detection.
     */
    private data class Model(
        override val userId: Long,
        override val guildId: Long,
        override val guild: Guild?,
        override val channelNames: Map<Long, String>,
        override val list: List<ChatListEntry>,
        override val channelId: Long = NO_CHANNEL,
        override val myRoleIds: Set<Long> = emptySet(),
        override val oldestMessageId: Long = NO_CHANNEL,
        override val newMessagesMarkerMessageId: Long = NO_CHANNEL,
        override val isSpoilerClickAllowed: Boolean = false,
    ) : WidgetChatListAdapter.Data

    private companion object {
        const val PAGE_SIZE = 25

        /** How close to the end of the loaded results the list must get before the next page. */
        const val PREFETCH_ROWS = 4

        /** The reference's sentinel for "these rows do not belong to one channel". */
        const val NO_CHANNEL = -1L
    }
}
