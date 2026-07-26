package com.discord.widgets.search.results

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.models.message.Message
import com.discord.stores.StoreChat
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.search.SearchStore
import com.discord.widgets.search.SearchStoreProvider

/**
 * The result half of the search screen.
 *
 * Rows are the ordinary chat rows, but every interaction collapses to one thing: jump to the message
 * in its real channel and close the search screen. A search result is a pointer, not a live message,
 * so reacting, replying and long-press actions are all deliberately inert here.
 */
class WidgetSearchResults : AppFragment(R.layout.widget_search_results) {

    private var adapter: WidgetChatListAdapter? = null

    private val viewModel: WidgetSearchResultsViewModel by appViewModels {
        WidgetSearchResultsViewModel()
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val recycler = view.findViewById<RecyclerView>(R.id.search_results_list)
        adapter = MGRecyclerAdapter.configure<ChatListEntry, WidgetChatListAdapter>(
            WidgetChatListAdapter(
                recycler,
                this,
                parentFragmentManager,
                SearchResultAdapterEventHandler(
                    searchStore = SearchStoreProvider.get(),
                    closeSearch = { activity?.finish() },
                ),
            ),
        ).apply {
            // Every result would otherwise be highlighted, since a search commonly filters on the
            // viewer's own mentions.
            mentionMeMessageLevelHighlighting = false
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe { state -> adapter?.data = state }
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter?.dispose()
    }

    /**
     * Turns any row interaction into a jump.
     *
     * [onInteractionStateUpdated] is also how pagination happens: the chat adapter reports when the
     * list is scrolled to the top, and that asks the store for the page before the oldest hit.
     */
    private class SearchResultAdapterEventHandler(
        private val searchStore: SearchStore,
        private val closeSearch: () -> Unit,
    ) : WidgetChatListAdapter.EventHandler {

        private var oldestMessageId: Long = 0L

        private fun jumpToChat(message: Message) {
            StoreStream.getMessagesLoader().jumpToMessage(message.channelId, message.id)
            closeSearch()
        }

        override fun onOldestMessageId(channelId: Long, oldestMessageId: Long) {
            this.oldestMessageId = oldestMessageId
        }

        override fun onInteractionStateUpdated(interactionState: StoreChat.InteractionState) {
            if (interactionState.isAtTop && oldestMessageId != Long.MAX_VALUE) {
                searchStore.loadMore(oldestMessageId)
            }
        }

        override fun onMessageClicked(message: Message, isThreadStarterMessage: Boolean) =
            jumpToChat(message)

        override fun onMessageLongClicked(
            message: Message,
            formattedMessage: CharSequence,
            isThreadStarterMessage: Boolean,
        ) = jumpToChat(message)

        override fun onMessageAuthorAvatarClicked(message: Message, guildId: Long) =
            jumpToChat(message)

        override fun onMessageAuthorNameClicked(message: Message, guildId: Long) =
            jumpToChat(message)

        override fun onMessageAuthorLongClicked(message: Message, guildId: Long?) =
            jumpToChat(message)

        override fun onMessageBlockedGroupClicked(message: Message) = jumpToChat(message)

        override fun onStickerClicked(message: Message, sticker: com.discord.api.sticker.BaseSticker) =
            jumpToChat(message)
    }
}
