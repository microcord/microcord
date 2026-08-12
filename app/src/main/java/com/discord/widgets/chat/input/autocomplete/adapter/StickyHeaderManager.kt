package com.discord.widgets.chat.input.autocomplete.adapter

import android.view.LayoutInflater
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.views.StickyHeaderItemDecoration

class StickyHeaderManager(
    val recyclerView: RecyclerView,
    val adapter: ChatInputAutocompleteAdapter,
) {
    val stickyHeaderHolder: StickyHeaderHolder = CommandHeaderViewHolder(
        LayoutInflater.from(recyclerView.context).inflate(
            R.layout.widget_chat_input_command_application_header_item,
            recyclerView,
            false,
        ),
        onIconLoaded = recyclerView::invalidate,
    )

    fun layoutViews(recyclerView: RecyclerView) =
        StickyHeaderItemDecoration.LayoutManager.layoutHeaderView(recyclerView, stickyHeaderHolder.getItemView())
}
