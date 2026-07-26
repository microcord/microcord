package com.discord.widgets.directories

import android.widget.TextView

/** The "Add Server" call to action row. `directory_add_server_list_item.xml` is a bare `TextView`. */
class AddServerViewHolder(
    private val actionText: TextView,
    adapter: WidgetDirectoryChannelAdapter,
) : DirectoryChannelViewHolder(actionText, adapter) {

    override fun bind(item: DirectoryChannelItem, listener: DirectoryChannelItemClickInterface) {
        actionText.setOnClickListener { listener.onAddServerClicked() }
    }
}
