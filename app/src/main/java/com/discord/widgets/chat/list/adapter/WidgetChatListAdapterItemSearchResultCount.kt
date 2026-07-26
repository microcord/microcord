package com.discord.widgets.chat.list.adapter

import android.widget.TextView
import com.discord.R
import com.discord.i18n.i18nPlural
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.SearchResultCountEntry

/**
 * The "N Results" header the chat list shows while it is displaying search results.
 *
 * The layout's root *is* the text view, so there is no child to look up.
 */
class WidgetChatListAdapterItemSearchResultCount(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_result_count, adapter) {

    private val resultCount: TextView = itemView.findViewById(R.id.result_count)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val totalResults = (data as SearchResultCountEntry).totalResults
        resultCount.text = resultCount.i18nPlural(
            R.plurals.total_results_count,
            totalResults,
            "count" to totalResults,
        )
    }
}
