package com.discord.widgets.chat.list.adapter

import android.widget.TextView
import com.discord.R
import com.discord.dialogs.SelectorDialog
import com.discord.i18n.i18nPlural
import com.discord.utilities.search.SearchSortMode
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.SearchResultCountEntry
import com.discord.widgets.search.SearchStoreProvider

/**
 * The "N Results" header the chat list shows while it is displaying search results, with the sort
 * control on its trailing edge.
 */
class WidgetChatListAdapterItemSearchResultCount(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_result_count, adapter) {

    private val resultCount: TextView = itemView.findViewById(R.id.result_count)
    private val resultSort: TextView = itemView.findViewById(R.id.result_sort)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val totalResults = (data as SearchResultCountEntry).totalResults
        resultCount.text = resultCount.i18nPlural(
            R.plurals.total_results_count,
            totalResults,
            "count" to totalResults,
        )

        val store = SearchStoreProvider.get().storeSearchQuery
        resultSort.setText(labelOf(store.getSortMode()))
        resultSort.setOnClickListener { view ->
            SelectorDialog.show(
                adapter.fragmentManager,
                view.context.getString(R.string.search_sort_by),
                SORT_OPTIONS.map { view.context.getString(labelOf(it)) as CharSequence }.toTypedArray(),
            ) { index -> store.setSortMode(SORT_OPTIONS[index]) }
        }
    }

    private companion object {
        val SORT_OPTIONS = listOf(
            SearchSortMode.NEWEST,
            SearchSortMode.OLDEST,
            SearchSortMode.MOST_RELEVANT,
        )

        fun labelOf(mode: SearchSortMode) = when (mode) {
            SearchSortMode.NEWEST -> R.string.search_newest
            SearchSortMode.OLDEST -> R.string.search_oldest
            SearchSortMode.MOST_RELEVANT -> R.string.search_most_relevant
        }
    }
}
