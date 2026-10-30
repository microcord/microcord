package com.discord.widgets.search.results

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.databinding.WidgetSearchResultsBinding
import com.discord.models.message.Message
import com.discord.stores.StoreChat
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.WidgetUrlActions
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.search.SearchStore
import com.discord.widgets.search.SearchStoreProvider
import com.discord.widgets.search.WidgetSearch

/**
 * The result half of the search screen.
 *
 * Rows are the ordinary chat rows, but every interaction collapses to one thing: jump to the message
 * in its real channel and close the search screen, keeping the results for Back from that channel. A search result is a pointer, not a live message,
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
                    showActions = { message, content ->
                        WidgetChatListActions.showForSearch(parentFragmentManager, message.channelId, message.id, content)
                    },
                    showUrlActions = { url -> WidgetUrlActions.launch(parentFragmentManager, url) },
                    closeSearch = ::closeForJump,
                ),
                stickToBottom = false,
            ),
        ).apply {
            // Every result would otherwise be highlighted, since a search commonly filters on the
            // viewer's own mentions.
            mentionMeMessageLevelHighlighting = false
        }
        if (!isRecreated) {
            restoreScroll(
                mostRecentIntent.getIntExtra(WidgetSearch.INTENT_EXTRA_SCROLL_POSITION, 0),
                mostRecentIntent.getIntExtra(WidgetSearch.INTENT_EXTRA_SCROLL_OFFSET, 0),
            )
        }
    }

    private fun closeForJump(channelId: Long) {
        val layoutManager = adapter?.layoutManager
        val position = layoutManager?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
        val offset = layoutManager?.findViewByPosition(position)?.let { it.top - layoutManager.paddingTop } ?: 0
        (parentFragment as? WidgetSearch)?.closeForJump(channelId, position, offset)
    }

    /** Back from a result jump: scrolls to where the user was once the kept results are bound. */
    private fun restoreScroll(position: Int, offset: Int) {
        val adapter = adapter ?: return
        if (position <= 0 && offset == 0) return
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = restore()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = restore()

            private fun restore() {
                if (adapter.itemCount <= position) return
                adapter.unregisterAdapterDataObserver(this)
                adapter.layoutManager?.scrollToPositionWithOffset(position, offset)
            }
        })
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
        /** Long-press opens the usual message actions (copy text, copy link, ...) instead of jumping. */
        private val showActions: (Message, CharSequence) -> Unit = { _, _ -> },
        private val showUrlActions: (String) -> Unit = {},
        private val closeSearch: (channelId: Long) -> Unit,
    ) : WidgetChatListAdapter.EventHandler {

        private var oldestMessageId: Long = 0L

        private fun jumpToChat(message: Message) {
            StoreStream.getMessagesLoader().jumpToMessage(message.channelId, message.id)
            closeSearch(message.channelId)
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
        ) = showActions(message, formattedMessage)

        override fun onMessageAuthorAvatarClicked(message: Message, guildId: Long) =
            jumpToChat(message)

        override fun onMessageAuthorNameClicked(message: Message, guildId: Long) =
            jumpToChat(message)

        override fun onMessageAuthorLongClicked(message: Message, guildId: Long?) =
            jumpToChat(message)

        override fun onMessageBlockedGroupClicked(message: Message) = jumpToChat(message)

        override fun onUrlLongClicked(url: String) = showUrlActions(url)

        override fun onStickerClicked(message: Message, sticker: com.discord.api.sticker.BaseSticker) =
            jumpToChat(message)
    }
}
