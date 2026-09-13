package com.discord.widgets.search.results

import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.databinding.WidgetSearchResultsBinding
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
        val recycler = WidgetSearchResultsBinding.bind(view).searchResultsList
        adapter = MGRecyclerAdapter.configure<ChatListEntry, WidgetChatListAdapter>(
            WidgetChatListAdapter(
                recycler,
                this,
                parentFragmentManager,
                SearchResultAdapterEventHandler(
                    searchStore = SearchStoreProvider.get(),
                    closeSearch = { activity?.finish() },
                ),
                stickToBottom = false,
            ),
        ).apply {
            // Every result would otherwise be highlighted, since a search commonly filters on the
            // viewer's own mentions.
            mentionMeMessageLevelHighlighting = false
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        adapter?.setHandlers()
        adapter?.onResume()
        viewModel.observeViewState().ui(this).subscribe { state -> adapter?.data = state }
    }

    override fun onPause() {
        super.onPause()
        adapter?.onPause()
        adapter?.disposeHandlers()
    }

    override fun onDestroyView() {
        adapter?.disposeHandlers()
        adapter?.dispose()
        adapter = null
        super.onDestroyView()
    }

    /**
     * Turns any row interaction into a jump.
     *
     * [onInteractionStateUpdated] is also how pagination happens: the chat adapter reports when the
     * list reaches the bottom, and that asks the store for the next page of search hits.
     */
    internal class SearchResultAdapterEventHandler(
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
            if (interactionState.isAtBottom && oldestMessageId > 0L && oldestMessageId != Long.MAX_VALUE) {
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
