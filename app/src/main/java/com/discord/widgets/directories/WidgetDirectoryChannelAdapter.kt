package com.discord.widgets.directories

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.views.directories.ServerDiscoveryItem

/**
 * The one adapter behind every directory list: the directory channel's category pages and the
 * directory search results.
 *
 * The reference reassigns the whole row list and calls `notifyDataSetChanged()`. This project
 * diffs instead ([MGRecyclerAdapterSimple]), so joining a server re-binds that one row rather than
 * rebuilding the page — which matters here because the join button's state is the only thing that
 * changed.
 */
class WidgetDirectoryChannelAdapter(
    recycler: RecyclerView,
    internal val clickListener: DirectoryChannelItemClickInterface,
) : MGRecyclerAdapterSimple<DirectoryChannelItem>(recycler) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, DirectoryChannelItem> {
        val inflater = LayoutInflater.from(parent.context)
        return when (DirectoryChannelItem.ViewType.findType(viewType)) {
            DirectoryChannelItem.ViewType.Item -> DirectoryEntryViewHolder(
                inflater.inflate(R.layout.directory_entry_list_item, parent, false) as ServerDiscoveryItem,
                this,
            )
            DirectoryChannelItem.ViewType.AddServer -> AddServerViewHolder(
                inflater.inflate(R.layout.directory_add_server_list_item, parent, false) as TextView,
                this,
            )
            DirectoryChannelItem.ViewType.SectionHeader -> SectionHeaderViewHolder(
                inflater.inflate(R.layout.directory_section_header_list_item, parent, false) as TextView,
                this,
            )
        }
    }
}
