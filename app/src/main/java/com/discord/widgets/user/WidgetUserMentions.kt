package com.discord.widgets.user

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.message.Message
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.ChatListEntry
import rx.Subscription
import rx.schedulers.Schedulers

class WidgetUserMentions : AppFragment(R.layout.widget_user_mentions) {
    private val adapter = WidgetChatListAdapter(onClick = ::openMessage)
    private val messages = ArrayList<Message>()
    private var request: Subscription? = null
    private var loading = false
    private var allLoaded = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<Toolbar>(R.id.action_bar_toolbar).setTitle(R.string.recent_mentions)
        view.findViewById<RecyclerView>(R.id.user_mentions_list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@WidgetUserMentions.adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val manager = recyclerView.layoutManager as LinearLayoutManager
                    if (dy > 0 && manager.findLastVisibleItemPosition() >= this@WidgetUserMentions.adapter.itemCount - 4) load()
                }
            })
        }
        load()
    }

    override fun onDestroyView() {
        request?.unsubscribe()
        request = null
        super.onDestroyView()
    }

    private fun load() {
        if (loading || allLoaded) return
        loading = true
        render()
        request = StoreStream.getMentions(messages.minOfOrNull(Message::id))
            .subscribeOn(Schedulers.io())
            .subscribe(
                { page -> view?.post {
                    val converted = page.map(::Message)
                    messages.addAll(converted.filter { incoming -> messages.none { it.id == incoming.id } })
                    messages.sortByDescending(Message::id)
                    allLoaded = page.size < PAGE_SIZE
                    loading = false
                    render()
                } },
                { view?.post { loading = false; render() } },
            )
    }

    private fun render() {
        val rows = messages.flatMap(::messageRows).toMutableList()
        if (loading) rows += ChatListEntry.Loading(0)
        adapter.submitList(rows)
    }

    private fun openMessage(message: Message) {
        val channel = StoreStream.getChannels().getChannel(message.channelId) ?: return
        StoreStream.selectChannel(channel.guildId, channel.id)
        StoreStream.getMessagesLoader().jumpToMessage(channel.id, message.id)
        StoreStream.getTabsNavigation().selectHomeTab(StoreNavigation.PanelAction.CLOSE, true)
    }

    private fun messageRows(message: Message): List<ChatListEntry> = buildList {
        add(ChatListEntry.Header(message, message.content.orEmpty()))
        message.attachments.orEmpty().forEachIndexed { index, attachment -> add(ChatListEntry.Attachment(message, attachment, index)) }
        message.reactions?.takeIf(List<*>::isNotEmpty)?.let { add(ChatListEntry.Reactions(message, it)) }
    }

    private companion object { const val PAGE_SIZE = 25 }
}
