package com.discord.widgets.channels.threads.browser

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.i18n.i18nFormat

class WidgetThreadBrowserAdapter(
    private val onOpenThread: (Channel) -> Unit,
    private val onThreadSettings: (Channel) -> Unit,
) : ListAdapter<WidgetThreadBrowserAdapter.Item, WidgetThreadBrowserAdapter.Holder>(DIFF) {

    sealed class Item(val key: String, val layout: Int) {
        data class Warning(val id: String = "warning") : Item(id, R.layout.thread_browser_item_warning)
        data class Thread(val threadData: ThreadBrowserThreadView.ThreadData) :
            Item(threadData.channel.id.toString(), R.layout.thread_browser_item_thread)
        data class Header(val id: String, val stringResId: Int, val count: Int) :
            Item(id, R.layout.thread_browser_item_header)
        data class Loading(val id: String = "loading") : Item(id, R.layout.thread_browser_item_loading)
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) = getItem(position).key.hashCode().toLong()
    override fun getItemViewType(position: Int) = getItem(position).layout

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(viewType, parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(item: Item) {
            itemView.setOnClickListener(null)
            itemView.setOnLongClickListener(null)
            when (item) {
                is Item.Warning -> itemView.findViewById<TextView>(R.id.warning)
                    .setText(R.string.moderator_view_description)
                is Item.Header -> itemView.findViewById<TextView>(R.id.header_name).text =
                    itemView.context.i18nFormat(item.stringResId, "count" to item.count)
                is Item.Thread -> (itemView as ThreadBrowserThreadView).apply {
                    setThreadData(item.threadData)
                    setOnClickListener { onOpenThread(item.threadData.channel) }
                    setOnLongClickListener {
                        onThreadSettings(item.threadData.channel)
                        true
                    }
                }
                is Item.Loading -> Unit
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: Item, newItem: Item) = oldItem == newItem
        }
    }
}
