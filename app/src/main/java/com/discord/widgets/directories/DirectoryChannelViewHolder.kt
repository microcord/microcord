package com.discord.widgets.directories

import android.view.View
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

/**
 * Base holder for every directory row.
 *
 * The reference derives straight from `RecyclerView.ViewHolder` because its adapter calls
 * `notifyDataSetChanged()`. This project's adapters diff instead, so the holder derives from
 * [MGRecyclerViewHolder] and [bind] is reached through `onConfigure`. Row behaviour is unchanged.
 */
abstract class DirectoryChannelViewHolder(
    view: View,
    adapter: WidgetDirectoryChannelAdapter,
) : MGRecyclerViewHolder<WidgetDirectoryChannelAdapter, DirectoryChannelItem>(view, adapter) {

    final override fun onConfigure(position: Int, data: DirectoryChannelItem) {
        bind(data, adapter.clickListener)
    }

    abstract fun bind(item: DirectoryChannelItem, listener: DirectoryChannelItemClickInterface)
}
