package com.discord.widgets.user.search

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.i18n.i18nFormat
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

/**
 * The quick-switcher result list.
 *
 * Selection is reported with the originating [View] so the host can hide the keyboard against it,
 * and with `isLongPress` so a long press can open the user sheet instead of navigating.
 */
class WidgetGlobalSearchAdapter(
    recycler: RecyclerView,
) : MGRecyclerAdapterSimple<WidgetGlobalSearchModel.ItemDataPayload>(recycler) {

    var onSelectedListener: (view: View, isLongPress: Boolean, data: WidgetGlobalSearchModel.ItemDataPayload) -> Unit =
        { _, _, _ -> }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, WidgetGlobalSearchModel.ItemDataPayload> = when (viewType) {
        WidgetGlobalSearchModel.TYPE_HEADER -> HeaderViewHolder(this)
        WidgetGlobalSearchModel.TYPE_SEARCH_MESSAGES -> SearchMessagesViewHolder(this)
        WidgetGlobalSearchModel.TYPE_CHANNEL,
        WidgetGlobalSearchModel.TYPE_GUILD,
        WidgetGlobalSearchModel.TYPE_USER,
        -> SearchViewHolder(this)
        else -> throw invalidViewTypeException(viewType)
    }

    private class HeaderViewHolder(
        adapter: WidgetGlobalSearchAdapter,
    ) : MGRecyclerViewHolder<WidgetGlobalSearchAdapter, WidgetGlobalSearchModel.ItemDataPayload>(
        R.layout.widget_channel_search_item_header,
        adapter,
    ) {
        private val label: TextView = itemView.findViewById(R.id.item_header_tv)

        override fun onConfigure(position: Int, data: WidgetGlobalSearchModel.ItemDataPayload) {
            super.onConfigure(position, data)
            label.setText((data as WidgetGlobalSearchModel.ItemHeader).name)
        }
    }

    /** "Search for …", the hand-off from the switcher to full message search. */
    private class SearchMessagesViewHolder(
        adapter: WidgetGlobalSearchAdapter,
    ) : MGRecyclerViewHolder<WidgetGlobalSearchAdapter, WidgetGlobalSearchModel.ItemDataPayload>(
        R.layout.widget_global_search_item_messages,
        adapter,
    ) {
        private val label: TextView = itemView.findViewById(R.id.global_search_messages_text)

        override fun onConfigure(position: Int, data: WidgetGlobalSearchModel.ItemDataPayload) {
            super.onConfigure(position, data)
            val item = data as WidgetGlobalSearchModel.ItemSearchMessages
            label.text = itemView.context.i18nFormat(
                R.string.search_from_suggestions,
                "suggestion" to item.query,
            )
            itemView.setOnClickListener { view -> adapter.onSelectedListener(view, false, data) }
        }
    }

    private class SearchViewHolder(
        adapter: WidgetGlobalSearchAdapter,
    ) : MGRecyclerViewHolder<WidgetGlobalSearchAdapter, WidgetGlobalSearchModel.ItemDataPayload>(
        R.layout.widget_channel_search_item,
        adapter,
    ) {
        private val itemViewGlobalSearch: ViewGlobalSearchItem = itemView as ViewGlobalSearchItem

        override fun onConfigure(position: Int, data: WidgetGlobalSearchModel.ItemDataPayload) {
            super.onConfigure(position, data)
            when (data) {
                is WidgetGlobalSearchModel.ItemChannel -> itemViewGlobalSearch.onConfigure(data)
                is WidgetGlobalSearchModel.ItemGuild -> itemViewGlobalSearch.onConfigure(data)
                is WidgetGlobalSearchModel.ItemUser -> itemViewGlobalSearch.onConfigure(data)
                else -> Unit
            }
            itemView.setOnClickListener { view -> adapter.onSelectedListener(view, false, data) }
            itemView.setOnLongClickListener { view ->
                adapter.onSelectedListener(view, true, data)
                true
            }
        }
    }
}
