package com.discord.widgets.chat.list.adapter

import android.widget.TextView
import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.SearchSpoilerResultEntry

/** A search hit from a spoiler channel the viewer has not revealed: channel name, no content. */
class WidgetChatListAdapterItemSearchSpoiler(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_search_spoiler, adapter) {

    private val channelName: TextView = itemView.findViewById(R.id.search_spoiler_channel)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as SearchSpoilerResultEntry
        val channel = StoreStream.getChannels().getChannel(entry.channelId)
        channelName.text = channel?.getDisplayNameOrDefault(channelName.context)
        itemView.setOnClickListener { ChannelSelector.getInstance().selectChannel(channel) }
    }
}
